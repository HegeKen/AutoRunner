package com.autorunner.core.execution

import com.autorunner.core.model.InputMode
import com.autorunner.core.platform.RootInputBackend
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.CoordinateResolver
import com.autorunner.core.model.DelayStep
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionOutcome
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.FailureStrategy
import com.autorunner.core.model.GamepadGateway
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.KeyStep
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.labelFor
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.ActionResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * 逐个动作执行 [ScriptModel]，可单次或重复运行。
 *
 * ```kotlin
 * val executor = AutoRunnerScriptExecutor(accessibilityController)
 * executor.execute(
 *     script = script,
 *     config = ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 10, intervalMs = 2000),
 * ) { progress -> overlay.update(progress) }
 * ```
 *
 * 设计要点：
 *
 * * **不忙等。** 暂停时协程停驻在 `state.first { … }` 上；动作间延时与循环间隔
 *   都使用 [ExecutionClock.delay]（生产环境即 `kotlinx.coroutines.delay`），
 *   因此空闲循环既不消耗 CPU 也不消耗电量。
 * * **服务丢失后可恢复。** 当手势因可恢复错误失败时，执行器会等待无障碍
 *   服务重新上线，并重试*同一个*动作，从而保留执行位置。
 * * **失败策略。** [FailureStrategy.SKIP_ACTION] 记一次失败后继续，
 *   [FailureStrategy.ABORT_SCRIPT] 则终止本次运行并报告原因。
 */
class AutoRunnerScriptExecutor(
    private val accessibilityController: AccessibilityController,
    private val gamepadGateway: GamepadGateway? = null,
    private val clock: ExecutionClock = SystemExecutionClock,
    /** Root 注入后端；未安装模块时为 `null`。 */
    private val rootBackend: RootInputBackend? = null,
    /** 当前选择的输入方式（设置里可切换）。 */
    private val inputMode: () -> InputMode = { InputMode.ACCESSIBILITY },
) {

    private val _state = MutableStateFlow(ExecutionState.IDLE)

    /** 当前生命周期状态，可被 UI 与各服务观察。 */
    val state: StateFlow<ExecutionState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(ExecutionProgress())

    /** 最新的进度快照；参见设计文档 §6.3.3。 */
    val progress: StateFlow<ExecutionProgress> = _progress.asStateFlow()

    private val _reports = MutableSharedFlow<ExecutionReport>(extraBufferCapacity = 8)

    /** 每次运行结束时发出一次。 */
    val reports: SharedFlow<ExecutionReport> = _reports.asSharedFlow()

    /**
     * 单次 run 独有的计数与起始时间。每次 [execute] 新建一份，
     * 旧 run 即使被新 run 淘汰，其尾部报告也只读自己那份，
     * 避免计数器/耗时被新 run 重置后跨 run 泄漏。
     */
    private class RunCounters {
        var completedLoops: Int = 0
        var executedActions: Int = 0
        var skippedActions: Int = 0
        var startMs: Long = 0L
    }

    /** 最近一次 run 的计数；供 pause/resume/emitStoppedReport 等外部入口读取。 */
    private var activeCounters: RunCounters? = null

    /** 单调递增的 run 标识；淘汰的旧 run 不得写回状态/进度/报告。 */
    private var runId = 0L

    /** 每次 run 独有的停止信号；[stop] 完成它以中断在执行协程。 */
    private var stopSignal: CompletableDeferred<Unit>? = null

    /** 有运行正在进行时为 `true`。 */
    val isActive: Boolean get() = _state.value.isActive

    /**
     * 按 [config] 执行 [script]。
     *
     * @param scriptId 写入进度事件的标识，供悬浮面板高亮正在运行的脚本。
     * @param onProgress 在每个动作边界与循环边界时回调。
     */
    suspend fun execute(
        script: ScriptModel,
        config: ExecutionConfig = script.execution,
        scriptId: String? = null,
        onProgress: (ExecutionProgress) -> Unit = {},
    ): ExecutionReport {
        if (_state.value.isActive) {
            return ExecutionReport(
                scriptId = scriptId,
                scriptName = script.displayName(),
                outcome = ExecutionOutcome.FAILED,
                completedLoops = 0,
                totalLoops = 0,
                executedActions = 0,
                skippedActions = 0,
                elapsedMs = 0L,
                failureMessage = "已有任务正在执行",
            )
        }

        val effective = config.sanitized()
        val currentRun = ++runId
        // 每次 run 独有的停止信号：stop() 完成它来中断本 run，
        // 旧 run 不会因新 run 覆盖 _state 而"复活"。
        val stopRequested = CompletableDeferred<Unit>()
        stopSignal = stopRequested
        val metrics = accessibilityController.refreshScreenMetrics()
        val steps = script.flow.map { CoordinateResolver.resolve(it, script.info.coordinateSpace, metrics) }
        val totalLoops = effective.totalLoops
        val scriptName = script.displayName()
        // 手柄按键位置按模式分别校准；由脚本决定它是针对哪种模式编写的。
        val gamepadMode = script.info.gamepadMode

        // 本 run 专属计数：即使后续有新 run 启动，本 run 的报告也只读这份。
        val counters = RunCounters().also {
            it.startMs = clock.nowMs()
            activeCounters = it
        }
        _state.value = ExecutionState.RUNNING

        var failureMessage: String? = null
        var stoppedByUser = false

        if (steps.isEmpty()) {
            failureMessage = "脚本不包含任何动作"
        } else {
            loop@ while (counters.completedLoops < totalLoops) {
                for ((index, step) in steps.withIndex()) {
                    awaitResumeOrStop()
                    if (stopRequested.isCompleted) {
                        stoppedByUser = true
                        break@loop
                    }

                    publishAction(
                        scriptId = scriptId,
                        scriptName = scriptName,
                        index = index,
                        totalActions = steps.size,
                        totalLoops = totalLoops,
                        // 进度文案里的手柄按键按脚本自身的手柄类型显示。
                        label = step.labelFor(gamepadMode),
                        message = null,
                        counters = counters,
                        onProgress = onProgress,
                    )

                    val outcome = if (step is DelayStep) {
                        // 纯等待不会进入手势层。
                        clock.delay(step.duration)
                        ActionResult.Success
                    } else {
                        dispatch(step, gamepadMode)
                    }
                    when (outcome) {
                        is ActionResult.Success -> counters.executedActions++

                        is ActionResult.Unsupported -> {
                            counters.skippedActions++
                            if (effective.failureStrategy == FailureStrategy.ABORT_SCRIPT) {
                                failureMessage = outcome.reason
                                break@loop
                            }
                        }

                        is ActionResult.Failure -> {
                            val recovered = outcome.recoverable &&
                                effective.reconnectTimeoutMs > 0L &&
                                awaitServiceReconnect(effective.reconnectTimeoutMs)
                            val retried = if (recovered) dispatch(step, gamepadMode) else outcome
                            if (retried.isSuccess) {
                                counters.executedActions++
                            } else {
                                counters.skippedActions++
                                val reason = (retried as? ActionResult.Failure)?.reason
                                    ?: (retried as? ActionResult.Unsupported)?.reason
                                    ?: "未知错误"
                                if (effective.failureStrategy == FailureStrategy.ABORT_SCRIPT) {
                                    failureMessage = reason
                                    break@loop
                                }
                                publishAction(
                                    scriptId = scriptId,
                                    scriptName = scriptName,
                                    index = index,
                                    totalActions = steps.size,
                                    totalLoops = totalLoops,
                                    label = step.labelFor(gamepadMode),
                                    message = "已跳过：$reason",
                                    counters = counters,
                                    onProgress = onProgress,
                                )
                            }
                        }
                    }

                    if (step.delay > 0L) clock.delay(step.delay)
                }

                if (stopRequested.isCompleted) {
                    stoppedByUser = true
                    break@loop
                }

                counters.completedLoops++
                publishAction(
                    scriptId = scriptId,
                    scriptName = scriptName,
                    index = -1,
                    totalActions = steps.size,
                    totalLoops = totalLoops,
                    label = "",
                    message = null,
                    counters = counters,
                    onProgress = onProgress,
                )

                if (counters.completedLoops < totalLoops) {
                    if (effective.restoreDelayMs > 0L &&
                        awaitGapInterruptedByStop(effective.restoreDelayMs, stopRequested)
                    ) {
                        stoppedByUser = true
                        break@loop
                    }
                    if (effective.intervalMs > 0L &&
                        awaitGapInterruptedByStop(
                            effective.intervalMs,
                            stopRequested,
                            // 上报粒度跟随文案精度：≥1 分钟的间隔按分钟报（14 分钟 → 15 次），
                            // 不足 1 分钟才按秒报，避免秒级刷新造成大量通知 IPC。
                            tickMs = if (effective.intervalMs >= 60_000L) 60_000L else GAP_TICK_MS,
                            onTick = { gapElapsed ->
                                // 间隔期间按粒度上报进度，让通知栏显示「间隔 7/14 分钟」。
                                if (currentRun == runId) {
                                    publishInterval(
                                        scriptId = scriptId,
                                        scriptName = scriptName,
                                        totalLoops = totalLoops,
                                        totalActions = steps.size,
                                        counters = counters,
                                        intervalTotalMs = effective.intervalMs,
                                        intervalElapsedMs = gapElapsed,
                                        onProgress = onProgress,
                                    )
                                }
                            },
                        )
                    ) {
                        stoppedByUser = true
                        break@loop
                    }
                }
            }
        }

        val outcome = when {
            failureMessage != null -> ExecutionOutcome.FAILED
            stoppedByUser || stopRequested.isCompleted -> ExecutionOutcome.STOPPED
            else -> ExecutionOutcome.COMPLETED
        }
        // 只有当前 run 才允许写回状态/进度/报告；被淘汰的旧 run 只安静退出。
        val isCurrentRun = currentRun == runId
        if (isCurrentRun) {
            _state.value = when (outcome) {
                ExecutionOutcome.FAILED, ExecutionOutcome.STOPPED -> ExecutionState.STOPPED
                ExecutionOutcome.COMPLETED -> ExecutionState.COMPLETED
            }
            if (stopSignal === stopRequested) stopSignal = null
        }

        val finalProgress = ExecutionProgress(
            state = _state.value,
            scriptId = scriptId,
            scriptName = scriptName,
            completedLoops = counters.completedLoops,
            totalLoops = totalLoops,
            currentActionIndex = -1,
            totalActions = steps.size,
            elapsedMs = elapsedSince(counters.startMs),
            skippedActions = counters.skippedActions,
            message = failureMessage,
        )
        if (isCurrentRun) {
            _progress.value = finalProgress
            onProgress(finalProgress)
        }

        val report = ExecutionReport(
            scriptId = scriptId,
            scriptName = scriptName,
            outcome = outcome,
            completedLoops = counters.completedLoops,
            totalLoops = totalLoops,
            executedActions = counters.executedActions,
            skippedActions = counters.skippedActions,
            elapsedMs = elapsedSince(counters.startMs),
            failureMessage = failureMessage,
        )
        if (isCurrentRun) _reports.tryEmit(report)
        return report
    }

    /** 暂停运行；协程停驻直到 [resume] 或 [stop]。 */
    fun pause() {
        if (_state.value == ExecutionState.RUNNING) {
            _state.value = ExecutionState.PAUSED
            _progress.value = _progress.value.copy(state = ExecutionState.PAUSED, elapsedMs = elapsedMs())
        }
    }

    /** 恢复已暂停的运行。 */
    fun resume() {
        if (_state.value == ExecutionState.PAUSED) {
            _state.value = ExecutionState.RUNNING
            _progress.value = _progress.value.copy(state = ExecutionState.RUNNING, elapsedMs = elapsedMs())
        }
    }

    /** 在运行与暂停之间切换。 */
    fun togglePause() {
        when (_state.value) {
            ExecutionState.RUNNING -> pause()
            ExecutionState.PAUSED -> resume()
            else -> Unit
        }
    }

    /** 请求终止；在下一个边界中断运行，包括循环间隔等待。 */
    fun stop() {
        if (_state.value.isActive) {
            _state.value = ExecutionState.STOPPED
            accessibilityController.cancelPendingGestures()
            // 完成本 run 的停止信号：正在 interval/restore 等待中的协程立即退出，
            // 不再等间隔走完后"复活"执行动作。
            stopSignal?.complete(Unit)
        }
    }

    /**
     * 代表某个运行发出终态 [ExecutionOutcome.STOPPED] 报告：其驱动协程被外部
     * 取消（见 [ExecutionController.forceStop]）。被取消的协程永远走不到
     * [execute] 末尾的报告发送，否则收集方将永远看不到该次运行结束。
     */
    fun emitStoppedReport(scriptId: String?, scriptName: String) {
        val snapshot = _progress.value
        val counters = activeCounters
        _reports.tryEmit(
            ExecutionReport(
                scriptId = scriptId,
                scriptName = scriptName,
                outcome = ExecutionOutcome.STOPPED,
                completedLoops = snapshot.completedLoops,
                totalLoops = snapshot.totalLoops,
                executedActions = counters?.executedActions ?: 0,
                skippedActions = counters?.skippedActions ?: 0,
                elapsedMs = elapsedMs(),
            ),
        )
    }

    /** 把终态重置回 [ExecutionState.IDLE]。 */
    fun reset() {
        if (!_state.value.isActive) {
            activeCounters = null
            _state.value = ExecutionState.IDLE
            // 完整清空进度快照：否则 completedLoops/scriptName 残留会让首页
            // 运行横幅（依赖 completedLoops > 0）在"清除"后仍然显示。
            _progress.value = ExecutionProgress()
        }
    }

    private suspend fun awaitResumeOrStop() {
        if (_state.value != ExecutionState.PAUSED) return
        _state.first { it != ExecutionState.PAUSED }
    }

    /**
     * 等待 [millis]（循环间隔/恢复延迟），但 [stopRequested] 完成时立即返回 `true`。
     * 这样 stop() 能中断间隔等待，旧 run 不会在间隔走完后继续执行动作。
     *
     * 传入 [onTick] 时把总时长切成 [tickMs] 的小段，每段结束回调一次已等待时长，
     * 用于通知栏显示「间隔 7/14 分钟」这类进度。粒度由调用方按文案精度决定
     * （分钟级间隔用 60 秒，秒级间隔用 [GAP_TICK_MS]），避免高频通知刷新。
     */
    private suspend fun awaitGapInterruptedByStop(
        millis: Long,
        stopRequested: CompletableDeferred<Unit>,
        tickMs: Long = GAP_TICK_MS,
        onTick: ((elapsedMs: Long) -> Unit)? = null,
    ): Boolean = coroutineScope {
        val elapsed = async {
            if (onTick == null) {
                clock.delay(millis)
            } else {
                var waited = 0L
                onTick(waited)
                while (waited < millis) {
                    val slice = minOf(tickMs, millis - waited)
                    clock.delay(slice)
                    waited += slice
                    onTick(waited)
                }
            }
            false
        }
        val stopped = async { stopRequested.await(); true }
        try {
            select {
                elapsed.onAwait { it }
                stopped.onAwait { it }
            }
        } finally {
            elapsed.cancel()
            stopped.cancel()
        }
    }

    /**
     * 等待无障碍服务重新绑定（超长运行期间系统可能会把它杀掉再重启）。
     */
    private suspend fun awaitServiceReconnect(timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (accessibilityController.isConnected) return true
            clock.delay(RECONNECT_POLL_MS)
            waited += RECONNECT_POLL_MS
        }
        return accessibilityController.isConnected
    }

    private suspend fun dispatch(step: ActionStep, gamepadMode: GamepadMode): ActionResult = when (step) {
        is GamepadStep -> when (val gateway = gamepadGateway) {
            null -> ActionResult.Unsupported("手柄模拟未启用")
            else -> runCatching { gateway.execute(step, gamepadMode) }.fold(
                onSuccess = { ActionResult.Success },
                onFailure = { ActionResult.Failure(it.message ?: "手柄指令发送失败") },
            )
        }

        is KeyStep -> when {
            // 文本输入由无障碍层的 setText 实现；Root 注入尚不支持文本，回落到无障碍
            accessibilityController.isConnected -> accessibilityController.perform(step)
            rootBackend?.takeIf { it.isAvailable } != null ->
                ActionResult.Unsupported("Root 注入暂不支持键盘文本输入，请切换到无障碍模式")
            else -> ActionResult.Failure("无障碍服务未连接", recoverable = true)
        }

        else -> {
            val root = rootBackend?.takeIf { it.isAvailable }
            when {
                // 用户选了 Root：直接用 root 注入（不依赖无障碍）
                inputMode() == InputMode.ROOT && root != null -> root.perform(step)
                accessibilityController.isConnected -> accessibilityController.perform(step)
                // 选了无障碍但它掉线时，只要模块在就自动回落，避免整段脚本失败
                root != null -> root.perform(step)
                else -> ActionResult.Failure("无障碍服务未连接", recoverable = true)
            }
        }
    }

    private fun publishAction(
        scriptId: String?,
        scriptName: String,
        index: Int,
        totalActions: Int,
        totalLoops: Int,
        label: String,
        message: String?,
        counters: RunCounters,
        onProgress: (ExecutionProgress) -> Unit,
    ) {
        val snapshot = ExecutionProgress(
            state = _state.value,
            scriptId = scriptId,
            scriptName = scriptName,
            completedLoops = counters.completedLoops,
            totalLoops = totalLoops,
            currentActionIndex = index,
            totalActions = totalActions,
            currentActionLabel = label,
            elapsedMs = elapsedSince(counters.startMs),
            skippedActions = counters.skippedActions,
            message = message,
        )
        _progress.value = snapshot
        onProgress(snapshot)
    }

    /**
     * 间隔等待期间上报一次进度快照，携带 [intervalTotalMs]/[intervalElapsedMs]，
     * 让通知栏实时显示「间隔 7/14 分钟」这类已过时长/总时长。
     */
    private fun publishInterval(
        scriptId: String?,
        scriptName: String,
        totalLoops: Int,
        totalActions: Int,
        counters: RunCounters,
        intervalTotalMs: Long,
        intervalElapsedMs: Long,
        onProgress: (ExecutionProgress) -> Unit,
    ) {
        val snapshot = ExecutionProgress(
            state = _state.value,
            scriptId = scriptId,
            scriptName = scriptName,
            completedLoops = counters.completedLoops,
            totalLoops = totalLoops,
            currentActionIndex = -1,
            totalActions = totalActions,
            elapsedMs = elapsedSince(counters.startMs),
            skippedActions = counters.skippedActions,
            intervalTotalMs = intervalTotalMs,
            intervalElapsedMs = intervalElapsedMs,
        )
        _progress.value = snapshot
        onProgress(snapshot)
    }

    /** 当前活跃 run 的已耗时；供 pause/resume 等外部入口使用。 */
    private fun elapsedMs(): Long = elapsedSince(activeCounters?.startMs ?: 0L)

    private fun elapsedSince(startMs: Long): Long = (clock.nowMs() - startMs).coerceAtLeast(0L)

    private companion object {
        const val RECONNECT_POLL_MS = 250L

        /** 间隔进度上报粒度：每 1 秒回调一次已等待时长（通知栏秒级刷新）。 */
        const val GAP_TICK_MS = 1_000L
    }
}
