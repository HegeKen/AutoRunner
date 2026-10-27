package com.autorunner.core.execution

import com.autorunner.core.model.ComboConfig
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionOutcome
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.util.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 持有驱动 [AutoRunnerScriptExecutor] 的协程，并暴露悬浮面板、通知和编辑器
 * 「试运行」按钮所使用的开始 / 暂停 / 恢复 / 停止接口。
 *
 * 除单脚本运行外，还支持**组合运行**：两段脚本各自持有一个执行器，在同一个
 * 组合里并行循环（见 [startCombo]）。组合运行的状态与进度会聚合成一份
 * [state] / [progress]，因此悬浮球、通知栏、屏幕常亮与输入监听无需区分
 * 「单脚本」还是「组合」；组合页额外读取 [comboProgress] 显示两个槽位的细节。
 */
class ExecutionController(
    private val executor: AutoRunnerScriptExecutor,
    private val scope: CoroutineScope,
    /**
     * 为组合运行的第二个槽位创建独立执行器。
     *
     * 为 `null` 时 [startCombo] 拒绝启动（桌面预览 / 极简测试容器没有第二个
     * 执行器）。生产容器在 `AutoRunnerCore.createContainer` 中注入。
     */
    private val executorFactory: (() -> AutoRunnerScriptExecutor)? = null,
) {

    private val _state = MutableStateFlow(ExecutionState.IDLE)

    /** 实时生命周期状态；组合运行时是两个槽位的聚合状态。 */
    val state: StateFlow<ExecutionState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(ExecutionProgress())

    /**
     * 最新的进度快照，可直接交给覆盖层渲染。
     *
     * 组合运行时这里是一份聚合快照（标题为「组合：A + B」、循环数为两段之和），
     * 逐槽位的明细见 [comboProgress]。
     */
    val progress: StateFlow<ExecutionProgress> = _progress.asStateFlow()

    private val _reports = MutableSharedFlow<ExecutionReport>(extraBufferCapacity = 16)

    /** 每次运行结束时发出一次；组合运行会先发两段脚本各自的报告，再发汇总报告。 */
    val reports: SharedFlow<ExecutionReport> = _reports.asSharedFlow()

    private val _lastComboReport = MutableStateFlow<ExecutionReport?>(null)

    /**
     * 最近一次组合运行的汇总报告；复位或开始新组合时清空。
     *
     * 用 [StateFlow] 而不是一次性事件，用户运行结束后从桌面回到组合页
     * （Activity / ViewModel 可能已重建）仍能看到结果。
     */
    val lastComboReport: StateFlow<ExecutionReport?> = _lastComboReport.asStateFlow()

    private val _comboProgress = MutableStateFlow<ComboProgress?>(null)

    /** 组合运行中两个槽位的实时进度；未处于组合运行（或已复位）时为 `null`。 */
    val comboProgress: StateFlow<ComboProgress?> = _comboProgress.asStateFlow()

    /** 当前已加载脚本的标识。 */
    var activeScriptId: String? = null
        private set

    var activeScriptName: String = ""
        private set

    private var job: Job? = null

    private var comboJob: Job? = null

    /** 当前组合运行上下文；`null` 表示处于单脚本模式。 */
    private val comboRun = MutableStateFlow<ComboRun?>(null)

    val isActive: Boolean get() = _state.value.isActive

    val isPaused: Boolean get() = _state.value == ExecutionState.PAUSED

    /**
     * 「待运行」标记：脚本已武装到悬浮球上、等用户点击开始。
     *
     * 键盘 / 鼠标映射（WASD、逐键点击、鼠标右摇杆）只应在 [armed] 为 `true`
     * 或 [state] 处于活动期（运行 / 暂停）时监听输入，其余时间必须放行，
     * 避免全天候拦截用户的键盘与指针事件。
     */
    private val _armed = MutableStateFlow(false)

    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _armedCombo = MutableStateFlow<ComboConfig?>(null)

    /** 已武装到悬浮球、等待单击开始的组合配置。 */
    val armedCombo: StateFlow<ComboConfig?> = _armedCombo.asStateFlow()

    init {
        // 单脚本模式：把执行器的状态 / 进度 / 报告转发为控制器自己的流。
        // 组合模式下由 publishComboSnapshot() 聚合两个执行器，这里必须让位。
        scope.launch {
            executor.state.collect { next ->
                if (comboRun.value == null) _state.value = next
            }
        }
        scope.launch {
            executor.progress.collect { next ->
                if (comboRun.value == null) _progress.value = next
            }
        }
        scope.launch {
            executor.reports.collect { report ->
                if (comboRun.value == null) _reports.tryEmit(report)
            }
        }
    }

    /** 进入「待运行」（悬浮球已就绪，等用户点击开始）。 */
    fun markArmed() {
        _armedCombo.value = null
        _armed.value = true
    }

    /** 进入「待运行」，但点击悬浮球时启动 [config] 描述的组合。 */
    fun armCombo(config: ComboConfig) {
        if (isActive) return
        _armedCombo.value = config.sanitized()
        _armed.value = true
    }

    /** 离开「待运行」（悬浮球被关闭，或武装请求被放弃）。 */
    fun disarm() {
        _armed.value = false
        _armedCombo.value = null
    }

    /**
     * 启动 [script]。
     *
     * @return 已有运行在进行中时返回 `false`。
     */
    fun start(
        script: ScriptModel,
        config: ExecutionConfig = script.execution,
        scriptId: String? = null,
    ): Boolean {
        if (isActive) {
            return false
        }
        clearFinishedCombo()
        // 「待运行」被本次启动消费；运行期间由 state.isActive 接管监听门控。
        _armed.value = false
        _armedCombo.value = null
        executor.reset()
        activeScriptId = scriptId
        activeScriptName = script.displayName()
        job = scope.launch {
            executor.execute(script = script, config = config, scriptId = scriptId)
        }
        return true
    }

    /**
     * 同时启动两段脚本，各自按 [ComboExecution] 里的执行配置循环。
     *
     * A 立即开始；B 若配置了 `startDelayMs`，会先进入「等待首轮」再执行第一轮。
     *
     * @return 已有运行在进行中、或容器没有为第二个槽位提供执行器时返回 `false`。
     */
    fun startCombo(execution: ComboExecution): Boolean {
        if (isActive) return false
        val factory = executorFactory ?: return false
        clearFinishedCombo()

        val run = ComboRun(
            // A 的首轮延迟强制为 0：组合的第一轮由 A 先开始。
            slotA = execution.slotA.copy(
                config = execution.slotA.config.copy(startDelayMs = 0L).sanitized(),
            ),
            slotB = execution.slotB.copy(
                config = execution.slotB.config.sanitized(),
            ),
            executorA = executor,
            executorB = factory(),
        )
        run.executorA.reset()
        run.executorB.reset()

        _armed.value = false
        _armedCombo.value = null
        _lastComboReport.value = null
        activeScriptId = null
        activeScriptName = comboName(execution)
        comboRun.value = run
        run.lastProgressAAtMs = currentTimeMillis()
        run.lastProgressBAtMs = currentTimeMillis()
        _state.value = ExecutionState.RUNNING
        publishComboSnapshot(run)

        // 先建立镜像：执行器开始跑之前就把后续每一份进度聚合到控制器。
        run.mirrors += scope.launch {
            run.executorA.progress.collect {
                run.lastProgressAAtMs = currentTimeMillis()
                publishComboSnapshot(run)
            }
        }
        run.mirrors += scope.launch {
            run.executorB.progress.collect {
                run.lastProgressBAtMs = currentTimeMillis()
                publishComboSnapshot(run)
            }
        }

        val deferredA = scope.async {
            run.executorA.execute(
                script = run.slotA.script,
                config = run.slotA.config,
                scriptId = run.slotA.scriptId,
            )
        }
        val deferredB = scope.async {
            run.executorB.execute(
                script = run.slotB.script,
                config = run.slotB.config,
                scriptId = run.slotB.scriptId,
            )
        }
        run.jobs += deferredA
        run.jobs += deferredB

        comboJob = scope.launch {
            val reportA = deferredA.await()
            val reportB = deferredB.await()
            if (comboRun.value !== run) return@launch
            run.finished = true
            publishComboSnapshot(run)
            // 先报告两段脚本各自的结果（脚本库的运行横幅仍能显示是哪一段结束），
            // 最后再发一条汇总，避免最终消息只覆盖其中一段。
            _reports.tryEmit(reportA)
            _reports.tryEmit(reportB)
            val combined = combineReports(reportA, reportB)
            _reports.tryEmit(combined)
            _lastComboReport.value = combined
        }
        return true
    }

    fun pause() {
        val run = comboRun.value
        if (run != null) {
            run.executorA.pause()
            run.executorB.pause()
            // 暂停 / 恢复时重置各槽位的时间基准，避免恢复后把暂停时长
            // 补进超级岛进度条的插值。
            val now = currentTimeMillis()
            run.lastProgressAAtMs = now
            run.lastProgressBAtMs = now
            publishComboSnapshot(run)
            return
        }
        executor.pause()
    }

    fun resume() {
        val run = comboRun.value
        if (run != null) {
            run.executorA.resume()
            run.executorB.resume()
            val now = currentTimeMillis()
            run.lastProgressAAtMs = now
            run.lastProgressBAtMs = now
            publishComboSnapshot(run)
            return
        }
        executor.resume()
    }

    fun togglePause() {
        when (_state.value) {
            ExecutionState.RUNNING -> pause()
            ExecutionState.PAUSED -> resume()
            else -> Unit
        }
    }

    /** 优雅停止：引擎完成当前动作后收尾退出；组合运行会同时停止两段脚本。 */
    fun stop() {
        _armed.value = false
        _armedCombo.value = null
        val run = comboRun.value
        if (run != null) {
            run.executorA.stop()
            run.executorB.stop()
            publishComboSnapshot(run)
            return
        }
        executor.stop()
    }

    /** 无障碍服务永久消失时使用的强制停止。 */
    fun forceStop() {
        _armed.value = false
        _armedCombo.value = null
        val run = comboRun.value
        if (run != null) {
            val wasActive = _state.value.isActive
            val counters = _comboProgress.value
            run.executorA.stop()
            run.executorB.stop()
            cancelComboWork(run)
            _state.value = ExecutionState.STOPPED
            counters?.let { _comboProgress.value = it.copy(state = ExecutionState.STOPPED) }
            _progress.value = _progress.value.copy(state = ExecutionState.STOPPED)
            if (wasActive) {
                // 取消协程意味着 execute() 走不到末尾的报告发送；代替它发布汇总报告。
                _lastComboReport.value = stoppedComboReport(run, counters)
            }
            return
        }
        val wasActive = job?.isActive == true
        executor.stop()
        job?.cancel()
        job = null
        if (wasActive) {
            // 取消 job 意味着 execute() 永远走不到末尾的终态报告发送；
            // 代替它发布 STOPPED 报告，避免 UI 侧的收集方一直等下去。
            executor.emitStoppedReport(activeScriptId, activeScriptName)
        }
    }

    /** 清除已结束的运行，让面板回到空闲状态。 */
    fun reset() {
        _armed.value = false
        _armedCombo.value = null
        val run = comboRun.value
        if (run != null) {
            // 运行中不允许复位，避免把仍在跑的两个执行器直接摘掉。
            if (_state.value.isActive) return
            clearComboRun(run)
            return
        }
        executor.reset()
        if (!_state.value.isActive) {
            _state.value = ExecutionState.IDLE
            _progress.value = ExecutionProgress()
        }
    }

    // --------------------------------------------------------------- 组合内部

    /** 组合运行的一次性上下文；生命周期与 [comboProgress] 的非空期一致。 */
    private class ComboRun(
        val slotA: ComboScriptSlot,
        val slotB: ComboScriptSlot,
        val executorA: AutoRunnerScriptExecutor,
        val executorB: AutoRunnerScriptExecutor,
    ) {
        val mirrors = mutableListOf<Job>()
        val jobs = mutableListOf<Job>()

        /** 两个槽位的 `execute()` 都返回后置为 `true`，用于把聚合状态收敛到终态。 */
        @Volatile
        var finished: Boolean = false

        /** 各槽位最近一次进度更新的挂钟时间，供超级岛进度条插值。 */
        @Volatile
        var lastProgressAAtMs: Long = 0L

        @Volatile
        var lastProgressBAtMs: Long = 0L
    }

    /** 把两个执行器的最新快照聚合成 [ComboProgress] 与通知用的 [ExecutionProgress]。 */
    private fun publishComboSnapshot(run: ComboRun) {
        if (comboRun.value !== run) return
        val progressA = run.executorA.progress.value
        val progressB = run.executorB.progress.value
        val stateA = run.executorA.state.value
        val stateB = run.executorB.state.value
        val aggregate = aggregateComboState(stateA, stateB, run.finished)
        val snapshot = ComboProgress(
            slots = listOf(
                ComboSlotProgress(
                    role = ComboSlotRole.A,
                    scriptId = run.slotA.scriptId,
                    scriptName = run.slotA.scriptName,
                    state = stateA,
                    progress = progressA,
                    loopDurationMs = run.slotA.script.estimatedDurationMs,
                    cycleDurationMs = cycleDurationMs(run.slotA),
                    updatedAtEpochMs = run.lastProgressAAtMs,
                ),
                ComboSlotProgress(
                    role = ComboSlotRole.B,
                    scriptId = run.slotB.scriptId,
                    scriptName = run.slotB.scriptName,
                    state = stateB,
                    progress = progressB,
                    loopDurationMs = run.slotB.script.estimatedDurationMs,
                    cycleDurationMs = cycleDurationMs(run.slotB),
                    updatedAtEpochMs = run.lastProgressBAtMs,
                ),
            ),
            state = aggregate,
            elapsedMs = maxOf(progressA.elapsedMs, progressB.elapsedMs),
            updatedAtEpochMs = currentTimeMillis(),
        )
        _comboProgress.value = snapshot
        _state.value = aggregate
        _progress.value = combinedProgress(snapshot, progressA, progressB)
    }

    /**
     * 聚合状态：只要有一段仍在运行 / 暂停，整体就是活动的；两段都结束时，
     * 任意一段被停止 / 失败都算停止，否则算完成。
     */
    private fun aggregateComboState(
        stateA: ExecutionState,
        stateB: ExecutionState,
        finished: Boolean,
    ): ExecutionState {
        if (stateA.isActive || stateB.isActive) {
            return if (stateA == ExecutionState.PAUSED || stateB == ExecutionState.PAUSED) {
                ExecutionState.PAUSED
            } else {
                ExecutionState.RUNNING
            }
        }
        return when {
            stateA == ExecutionState.COMPLETED && stateB == ExecutionState.COMPLETED ->
                ExecutionState.COMPLETED

            stateA == ExecutionState.STOPPED || stateB == ExecutionState.STOPPED ->
                ExecutionState.STOPPED

            finished -> ExecutionState.COMPLETED
            // 启动瞬间两个执行器可能还没把状态写成 RUNNING；保持活动态，
            // 否则悬浮球会在第一帧误判为已完成。
            else -> ExecutionState.RUNNING
        }
    }

    /**
     * 通知栏 / 运行横幅使用的聚合进度。
     *
     * 循环数取两段之和；无限循环的脚本会让整体也显示为无限。间隔与首轮等待
     * 只取当前正在等待的那一段，保证通知里的「7/14 分钟」不会出现两个来源。
     */
    private fun combinedProgress(
        combo: ComboProgress,
        progressA: ExecutionProgress,
        progressB: ExecutionProgress,
    ): ExecutionProgress {
        val infinite = progressA.totalLoops >= ExecutionConfig.INFINITE_LOOPS ||
            progressB.totalLoops >= ExecutionConfig.INFINITE_LOOPS
        val totalLoops = if (infinite) {
            ExecutionConfig.INFINITE_LOOPS
        } else {
            (progressA.totalLoops.toLong() + progressB.totalLoops)
                .coerceAtMost(ExecutionConfig.INFINITE_LOOPS.toLong())
                .toInt()
        }
        val nameA = combo.slots.getOrNull(0)?.scriptName.orEmpty().ifBlank { progressA.scriptName }
        val nameB = combo.slots.getOrNull(1)?.scriptName.orEmpty().ifBlank { progressB.scriptName }
        val waiting = combo.slots.firstOrNull { it.isWaitingForFirstRound }
        val interval = combo.slots.firstOrNull { it.isInLoopInterval }
        return ExecutionProgress(
            state = combo.state,
            scriptId = null,
            scriptName = "组合：$nameA + $nameB",
            completedLoops = (progressA.completedLoops.toLong() + progressB.completedLoops)
                .coerceAtMost(ExecutionConfig.INFINITE_LOOPS.toLong())
                .toInt(),
            totalLoops = totalLoops,
            currentActionIndex = -1,
            totalActions = 0,
            elapsedMs = combo.elapsedMs,
            skippedActions = progressA.skippedActions + progressB.skippedActions,
            intervalTotalMs = interval?.progress?.intervalTotalMs ?: 0L,
            intervalElapsedMs = interval?.progress?.intervalElapsedMs ?: 0L,
            startDelayTotalMs = waiting?.progress?.startDelayTotalMs ?: 0L,
            startDelayElapsedMs = waiting?.progress?.startDelayElapsedMs ?: 0L,
        )
    }

    /** 一个槽位从本轮开始到下一轮开始的估算总时长。 */
    private fun cycleDurationMs(slot: ComboScriptSlot): Long =
        slot.script.estimatedDurationMs +
            slot.config.intervalMs.coerceAtLeast(0L) +
            slot.config.restoreDelayMs.coerceAtLeast(0L)

    private fun combineReports(a: ExecutionReport, b: ExecutionReport): ExecutionReport {
        val outcome = when {
            a.outcome == ExecutionOutcome.FAILED || b.outcome == ExecutionOutcome.FAILED ->
                ExecutionOutcome.FAILED

            a.outcome == ExecutionOutcome.STOPPED || b.outcome == ExecutionOutcome.STOPPED ->
                ExecutionOutcome.STOPPED

            else -> ExecutionOutcome.COMPLETED
        }
        val totalLoops = if (
            a.totalLoops >= ExecutionConfig.INFINITE_LOOPS ||
            b.totalLoops >= ExecutionConfig.INFINITE_LOOPS
        ) {
            ExecutionConfig.INFINITE_LOOPS
        } else {
            a.totalLoops + b.totalLoops
        }
        val failures = listOfNotNull(
            a.failureMessage?.let { "A：$it" },
            b.failureMessage?.let { "B：$it" },
        )
        return ExecutionReport(
            scriptId = null,
            scriptName = "组合：${a.scriptName} + ${b.scriptName}",
            outcome = outcome,
            completedLoops = a.completedLoops + b.completedLoops,
            totalLoops = totalLoops,
            executedActions = a.executedActions + b.executedActions,
            skippedActions = a.skippedActions + b.skippedActions,
            elapsedMs = maxOf(a.elapsedMs, b.elapsedMs),
            failureMessage = failures.takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
    }

    private fun stoppedComboReport(run: ComboRun, counters: ComboProgress?): ExecutionReport =
        ExecutionReport(
            scriptId = null,
            scriptName = activeScriptName.ifBlank { comboName(run) },
            outcome = ExecutionOutcome.STOPPED,
            completedLoops = counters?.slots?.sumOf { it.progress.completedLoops } ?: 0,
            totalLoops = ExecutionConfig.INFINITE_LOOPS,
            executedActions = 0,
            skippedActions = counters?.slots?.sumOf { it.progress.skippedActions } ?: 0,
            elapsedMs = counters?.elapsedMs ?: 0L,
        )

    private fun comboName(execution: ComboExecution): String =
        "组合：${execution.slotA.scriptName} + ${execution.slotB.scriptName}"

    private fun comboName(run: ComboRun): String =
        "组合：${run.slotA.scriptName} + ${run.slotB.scriptName}"

    /** 单脚本启动前清理已结束的组合，避免两套状态同时存在。 */
    private fun clearFinishedCombo() {
        val run = comboRun.value ?: return
        if (_state.value.isActive) return
        clearComboRun(run)
    }

    private fun clearComboRun(run: ComboRun) {
        cancelComboWork(run)
        run.executorA.reset()
        run.executorB.reset()
        if (comboRun.value === run) comboRun.value = null
        _comboProgress.value = null
        _lastComboReport.value = null
        _state.value = ExecutionState.IDLE
        _progress.value = ExecutionProgress()
    }

    private fun cancelComboWork(run: ComboRun) {
        comboJob?.cancel()
        comboJob = null
        run.mirrors.forEach { it.cancel() }
        run.jobs.forEach { it.cancel() }
    }
}
