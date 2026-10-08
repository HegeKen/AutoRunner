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

/**
 * Executes a [ScriptModel] action by action, once or repeatedly.
 *
 * ```kotlin
 * val executor = AutoRunnerScriptExecutor(accessibilityController)
 * executor.execute(
 *     script = script,
 *     config = ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 10, intervalMs = 2000),
 * ) { progress -> overlay.update(progress) }
 * ```
 *
 * Design notes:
 *
 * * **No busy waiting.** Pausing parks the coroutine on `state.first { … }` and
 *   both the per-action delays and the inter-loop interval use
 *   [ExecutionClock.delay] (`kotlinx.coroutines.delay` in production), so an
 *   idle loop costs no CPU and no battery.
 * * **Resumable after service loss.** When a gesture fails with a recoverable
 *   error the executor waits for the accessibility service to come back and
 *   retries the *same* action, which preserves the execution position.
 * * **Failure policy.** [FailureStrategy.SKIP_ACTION] counts the failure and
 *   continues, [FailureStrategy.ABORT_SCRIPT] unwinds the run and reports why.
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

    /** Current lifecycle state, observable by the UI and the services. */
    val state: StateFlow<ExecutionState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(ExecutionProgress())

    /** Latest progress snapshot; see §6.3.3 of the design document. */
    val progress: StateFlow<ExecutionProgress> = _progress.asStateFlow()

    private val _reports = MutableSharedFlow<ExecutionReport>(extraBufferCapacity = 8)

    /** Emitted once per finished run. */
    val reports: SharedFlow<ExecutionReport> = _reports.asSharedFlow()

    private var completedLoops = 0
    private var executedActions = 0
    private var skippedActions = 0
    private var startMs = 0L

    /** `true` while a run is in flight. */
    val isActive: Boolean get() = _state.value.isActive

    /**
     * Runs [script] honouring [config].
     *
     * @param scriptId identifier stored in the emitted progress, so that the
     *   floating panel can highlight the script that is running.
     * @param onProgress invoked on every action boundary and loop boundary.
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
        val metrics = accessibilityController.refreshScreenMetrics()
        val steps = script.flow.map { CoordinateResolver.resolve(it, script.info.coordinateSpace, metrics) }
        val totalLoops = effective.totalLoops
        val scriptName = script.displayName()
        // Gamepad button positions are calibrated per mode; the script decides which
        // one it was authored against.
        val gamepadMode = script.info.gamepadMode

        completedLoops = 0
        executedActions = 0
        skippedActions = 0
        startMs = clock.nowMs()
        _state.value = ExecutionState.RUNNING

        var failureMessage: String? = null
        var stoppedByUser = false

        if (steps.isEmpty()) {
            failureMessage = "脚本不包含任何动作"
        } else {
            loop@ while (completedLoops < totalLoops) {
                for ((index, step) in steps.withIndex()) {
                    awaitResumeOrStop()
                    if (_state.value == ExecutionState.STOPPED) {
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
                        onProgress = onProgress,
                    )

                    val outcome = if (step is DelayStep) {
                        // A pure wait never reaches the gesture layer.
                        clock.delay(step.duration)
                        ActionResult.Success
                    } else {
                        dispatch(step, gamepadMode)
                    }
                    when (outcome) {
                        is ActionResult.Success -> executedActions++

                        is ActionResult.Unsupported -> {
                            skippedActions++
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
                                executedActions++
                            } else {
                                skippedActions++
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
                                    onProgress = onProgress,
                                )
                            }
                        }
                    }

                    if (step.delay > 0L) clock.delay(step.delay)
                }

                if (_state.value == ExecutionState.STOPPED) {
                    stoppedByUser = true
                    break@loop
                }

                completedLoops++
                publishAction(
                    scriptId = scriptId,
                    scriptName = scriptName,
                    index = -1,
                    totalActions = steps.size,
                    totalLoops = totalLoops,
                    label = "",
                    message = null,
                    onProgress = onProgress,
                )

                if (completedLoops < totalLoops) {
                    if (effective.restoreDelayMs > 0L) clock.delay(effective.restoreDelayMs)
                    if (effective.intervalMs > 0L) clock.delay(effective.intervalMs)
                }
            }
        }

        val outcome = when {
            failureMessage != null -> ExecutionOutcome.FAILED
            stoppedByUser || _state.value == ExecutionState.STOPPED -> ExecutionOutcome.STOPPED
            else -> ExecutionOutcome.COMPLETED
        }
        _state.value = when (outcome) {
            ExecutionOutcome.FAILED, ExecutionOutcome.STOPPED -> ExecutionState.STOPPED
            ExecutionOutcome.COMPLETED -> ExecutionState.COMPLETED
        }

        val finalProgress = ExecutionProgress(
            state = _state.value,
            scriptId = scriptId,
            scriptName = scriptName,
            completedLoops = completedLoops,
            totalLoops = totalLoops,
            currentActionIndex = -1,
            totalActions = steps.size,
            elapsedMs = elapsedMs(),
            skippedActions = skippedActions,
            message = failureMessage,
        )
        _progress.value = finalProgress
        onProgress(finalProgress)

        val report = ExecutionReport(
            scriptId = scriptId,
            scriptName = scriptName,
            outcome = outcome,
            completedLoops = completedLoops,
            totalLoops = totalLoops,
            executedActions = executedActions,
            skippedActions = skippedActions,
            elapsedMs = elapsedMs(),
            failureMessage = failureMessage,
        )
        _reports.tryEmit(report)
        return report
    }

    /** Pauses the run; the coroutine parks until [resume] or [stop]. */
    fun pause() {
        if (_state.value == ExecutionState.RUNNING) {
            _state.value = ExecutionState.PAUSED
            _progress.value = _progress.value.copy(state = ExecutionState.PAUSED, elapsedMs = elapsedMs())
        }
    }

    /** Resumes a paused run. */
    fun resume() {
        if (_state.value == ExecutionState.PAUSED) {
            _state.value = ExecutionState.RUNNING
            _progress.value = _progress.value.copy(state = ExecutionState.RUNNING, elapsedMs = elapsedMs())
        }
    }

    /** Toggles between running and paused. */
    fun togglePause() {
        when (_state.value) {
            ExecutionState.RUNNING -> pause()
            ExecutionState.PAUSED -> resume()
            else -> Unit
        }
    }

    /** Requests termination; the loop exits at the next action boundary. */
    fun stop() {
        if (_state.value.isActive) {
            _state.value = ExecutionState.STOPPED
            accessibilityController.cancelPendingGestures()
        }
    }

    /**
     * Emits a terminal [ExecutionOutcome.STOPPED] report on behalf of a run
     * whose driving coroutine was cancelled externally (see
     * [ExecutionController.forceStop]): the cancelled coroutine never reaches
     * the report emission at the end of [execute], so collectors would
     * otherwise never see the run end.
     */
    fun emitStoppedReport(scriptId: String?, scriptName: String) {
        val snapshot = _progress.value
        _reports.tryEmit(
            ExecutionReport(
                scriptId = scriptId,
                scriptName = scriptName,
                outcome = ExecutionOutcome.STOPPED,
                completedLoops = snapshot.completedLoops,
                totalLoops = snapshot.totalLoops,
                executedActions = executedActions,
                skippedActions = skippedActions,
                elapsedMs = elapsedMs(),
            ),
        )
    }

    /** Resets a terminal state back to [ExecutionState.IDLE]. */
    fun reset() {
        if (!_state.value.isActive) {
            completedLoops = 0
            executedActions = 0
            skippedActions = 0
            startMs = 0L
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
     * Waits for the accessibility service to be rebound (the system may kill
     * and restart it during very long runs).
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
        onProgress: (ExecutionProgress) -> Unit,
    ) {
        val snapshot = ExecutionProgress(
            state = _state.value,
            scriptId = scriptId,
            scriptName = scriptName,
            completedLoops = completedLoops,
            totalLoops = totalLoops,
            currentActionIndex = index,
            totalActions = totalActions,
            currentActionLabel = label,
            elapsedMs = elapsedMs(),
            skippedActions = skippedActions,
            message = message,
        )
        _progress.value = snapshot
        onProgress(snapshot)
    }

    private fun elapsedMs(): Long = (clock.nowMs() - startMs).coerceAtLeast(0L)

    private companion object {
        const val RECONNECT_POLL_MS = 250L
    }
}
