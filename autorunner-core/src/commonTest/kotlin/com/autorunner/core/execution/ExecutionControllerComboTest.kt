package com.autorunner.core.execution

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ExecutionOutcome
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptInfo
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.TapStep
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 组合运行（两段脚本并行 + 首轮偏移）的控制器测试。
 *
 * 两个执行器共享同一个 [VirtualExecutionClock]，因此测试里的「A 运行 10 分钟后
 * B 才开始」是确定性的逻辑时间，不需要真的等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionControllerComboTest {

    /** 组合编排使用独立 scope：`backgroundScope` 的任务不会被 `advanceUntilIdle` 推进。 */
    private fun TestScope.controllerScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler))

    /**
     * 以 `runTest` 的虚拟时间为准的时钟：两个执行器的 delay 会像真实挂钟一样重叠，
     * 而不是把各自等待的时长相加。
     */
    private class SchedulerClock(
        private val scheduler: TestCoroutineScheduler,
    ) : ExecutionClock {
        override fun nowMs(): Long = scheduler.currentTime

        override suspend fun delay(millis: Long) {
            if (millis > 0L) kotlinx.coroutines.delay(millis)
        }
    }

    private class RecordingController(
        private val clock: ExecutionClock,
    ) : AccessibilityController {

        data class Dispatch(val step: ActionStep, val atMs: Long)

        val dispatches = mutableListOf<Dispatch>()

        override val isConnected: Boolean = true
        override val supportsGestures: Boolean = true
        override val screenMetrics: ScreenMetrics = ScreenMetrics(1080, 2400, 2.75f)

        override fun refreshScreenMetrics(): ScreenMetrics = screenMetrics

        override suspend fun perform(step: ActionStep): ActionResult {
            dispatches += Dispatch(step, clock.nowMs())
            return ActionResult.Success
        }

        override fun cancelPendingGestures() = Unit
    }

    private fun script(name: String, tapX: Float): ScriptModel = ScriptModel(
        info = ScriptInfo(name = name),
        flow = listOf(TapStep(tapX, 1f, delay = 0L)),
    )

    private fun slot(
        role: ComboSlotRole,
        script: ScriptModel,
        config: ExecutionConfig,
    ): ComboScriptSlot = ComboScriptSlot(
        role = role,
        scriptId = script.info.name,
        script = script,
        config = config,
    )

    @Test
    fun bothSlotsRunInParallelAndHonourTheFirstRoundOffset() = runTest {
        val clock = SchedulerClock(testScheduler)
        val access = RecordingController(clock)
        val gate = ActionDispatchGate()
        val executorA = AutoRunnerScriptExecutor(access, clock = clock, dispatchGate = gate)
        val executorB = AutoRunnerScriptExecutor(access, clock = clock, dispatchGate = gate)
        val scope = controllerScope()
        val controller = ExecutionController(
            executor = executorA,
            scope = scope,
            executorFactory = { executorB },
        )
        val reports = mutableListOf<ExecutionReport>()
        scope.launch { controller.reports.collect { reports += it } }

        val started = controller.startCombo(
            ComboExecution(
                slotA = slot(
                    ComboSlotRole.A,
                    script("A", tapX = 1f),
                    ExecutionConfig(
                        mode = ExecutionMode.REPEAT,
                        repeatCount = 3,
                        intervalMs = 1_000L,
                        // A 的首轮延迟应被控制器强制归零。
                        startDelayMs = 9_999L,
                    ),
                ),
                slotB = slot(
                    ComboSlotRole.B,
                    script("B", tapX = 2f),
                    ExecutionConfig(
                        mode = ExecutionMode.REPEAT,
                        repeatCount = 2,
                        intervalMs = 5_000L,
                        startDelayMs = 2_000L,
                    ),
                ),
            ),
        )
        assertTrue(started)
        assertTrue(controller.isActive)

        advanceUntilIdle()

        val aTaps = access.dispatches.filter { (it.step as TapStep).x == 1f }
        val bTaps = access.dispatches.filter { (it.step as TapStep).x == 2f }
        assertEquals(3, aTaps.size)
        assertEquals(2, bTaps.size)
        assertEquals(0L, aTaps.first().atMs, "A 必须在组合启动时立即开始第一轮")
        // B 的第一轮必须在首轮延迟之后才开始；此后按自己的 5 秒间隔循环。
        assertTrue(bTaps.first().atMs >= 2_000L, "B 首轮应等待 2 秒，实际 ${bTaps.first().atMs}ms")
        assertEquals(2_000L, bTaps.first().atMs)
        assertEquals(7_000L, bTaps.last().atMs)

        assertEquals(ExecutionState.COMPLETED, controller.state.value)
        val combo = assertNotNull(controller.comboProgress.value)
        assertEquals(ExecutionState.COMPLETED, combo.state)
        assertEquals(3, combo.slot(ComboSlotRole.A)?.progress?.completedLoops)
        assertEquals(2, combo.slot(ComboSlotRole.B)?.progress?.completedLoops)
        assertEquals(7_000L, combo.elapsedMs)

        // 两段脚本各自的报告 + 一条汇总报告。
        assertEquals(3, reports.size)
        assertEquals(ExecutionOutcome.COMPLETED, reports.last().outcome)
        assertEquals(5, reports.last().completedLoops)
        // 汇总报告以 StateFlow 保留，用户运行结束后重新打开组合页仍能看到结果。
        assertEquals(5, controller.lastComboReport.value?.completedLoops)
    }

    @Test
    fun theSecondSlotReportsWaitingForItsFirstRound() = runTest {
        val clock = SchedulerClock(testScheduler)
        val access = RecordingController(clock)
        val gate = ActionDispatchGate()
        val executorA = AutoRunnerScriptExecutor(access, clock = clock, dispatchGate = gate)
        val executorB = AutoRunnerScriptExecutor(access, clock = clock, dispatchGate = gate)
        val controller = ExecutionController(executorA, controllerScope()) { executorB }

        controller.startCombo(
            ComboExecution(
                slotA = slot(
                    ComboSlotRole.A,
                    script("A", 1f),
                    ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 0, intervalMs = 1_000L),
                ),
                slotB = slot(
                    ComboSlotRole.B,
                    script("B", 2f),
                    ExecutionConfig(
                        mode = ExecutionMode.REPEAT,
                        repeatCount = 0,
                        intervalMs = 1_000L,
                        startDelayMs = 10 * 60_000L,
                    ),
                ),
            ),
        )

        repeat(3) { yield() }
        val waiting = assertNotNull(controller.comboProgress.value?.slot(ComboSlotRole.B))
        assertTrue(waiting.isWaitingForFirstRound, "B 应处于等待首轮状态：${waiting.describeState()}")
        assertNotNull(waiting.progress.startDelayLabel)

        controller.stop()
        advanceUntilIdle()
        assertEquals(ExecutionState.STOPPED, controller.state.value)

        controller.reset()
        assertEquals(ExecutionState.IDLE, controller.state.value)
        assertNull(controller.comboProgress.value)
        assertNull(controller.lastComboReport.value)
    }

    @Test
    fun stopStopsBothSlots() = runTest {
        val clock = SchedulerClock(testScheduler)
        val access = RecordingController(clock)
        val gate = ActionDispatchGate()
        val executorA = AutoRunnerScriptExecutor(access, clock = clock, dispatchGate = gate)
        val executorB = AutoRunnerScriptExecutor(access, clock = clock, dispatchGate = gate)
        val controller = ExecutionController(executorA, controllerScope()) { executorB }

        controller.startCombo(
            ComboExecution(
                slotA = slot(
                    ComboSlotRole.A,
                    script("A", 1f),
                    ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 0, intervalMs = 60_000L),
                ),
                slotB = slot(
                    ComboSlotRole.B,
                    script("B", 2f),
                    ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 0, intervalMs = 60_000L),
                ),
            ),
        )

        repeat(4) { yield() }
        assertTrue(controller.isActive)
        controller.stop()
        advanceUntilIdle()

        assertEquals(ExecutionState.STOPPED, controller.state.value)
        assertEquals(ExecutionState.STOPPED, assertNotNull(controller.comboProgress.value).state)
        assertFalse(controller.isActive)
    }

    @Test
    fun pauseAndResumeApplyToBothSlots() = runTest {
        val clock = SchedulerClock(testScheduler)
        val access = RecordingController(clock)
        val gate = ActionDispatchGate()
        val executorA = AutoRunnerScriptExecutor(access, clock = clock, dispatchGate = gate)
        val executorB = AutoRunnerScriptExecutor(access, clock = clock, dispatchGate = gate)
        val controller = ExecutionController(executorA, controllerScope()) { executorB }

        controller.startCombo(
            ComboExecution(
                slotA = slot(
                    ComboSlotRole.A,
                    script("A", 1f),
                    ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 0, intervalMs = 1_000L),
                ),
                slotB = slot(
                    ComboSlotRole.B,
                    script("B", 2f),
                    ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 0, intervalMs = 1_000L),
                ),
            ),
        )

        repeat(3) { yield() }
        controller.pause()
        assertEquals(ExecutionState.PAUSED, controller.state.value)
        val dispatchedWhilePaused = access.dispatches.size
        repeat(10) { yield() }
        assertEquals(dispatchedWhilePaused, access.dispatches.size, "暂停期间不得派发动作")

        controller.resume()
        assertEquals(ExecutionState.RUNNING, controller.state.value)
        controller.stop()
        advanceUntilIdle()
    }

    @Test
    fun comboRequiresASecondExecutorAndClearsAfterReset() = runTest {
        val clock = SchedulerClock(testScheduler)
        val executor = AutoRunnerScriptExecutor(RecordingController(clock), clock = clock)
        val controller = ExecutionController(executor, controllerScope())

        val execution = ComboExecution(
            slotA = slot(
                ComboSlotRole.A,
                script("A", 1f),
                ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 1),
            ),
            slotB = slot(
                ComboSlotRole.B,
                script("B", 2f),
                ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 1, startDelayMs = 1_000L),
            ),
        )
        assertFalse(controller.startCombo(execution), "没有第二个执行器时必须拒绝启动")
        assertNull(controller.comboProgress.value)
    }
}
