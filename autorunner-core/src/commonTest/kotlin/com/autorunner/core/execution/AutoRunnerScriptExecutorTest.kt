package com.autorunner.core.execution

import com.autorunner.core.platform.ActionResult
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ExecutionOutcome
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.FailureStrategy
import com.autorunner.core.model.GamepadAction
import com.autorunner.core.model.GamepadButton
import com.autorunner.core.model.GamepadGateway
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.TapStep
import com.autorunner.core.platform.AccessibilityController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutoRunnerScriptExecutorTest {

    private class FakeController(
        override var isConnected: Boolean = true,
        private val failFirst: Boolean = false,
    ) : AccessibilityController {
        val dispatched = mutableListOf<ActionStep>()
        var failNext = failFirst
        var cancellations = 0

        override val supportsGestures: Boolean = true
        override val screenMetrics: ScreenMetrics = ScreenMetrics(1080, 2400, 2.75f)

        override fun refreshScreenMetrics(): ScreenMetrics = screenMetrics

        override suspend fun perform(step: ActionStep): ActionResult {
            if (failNext) {
                failNext = false
                return ActionResult.Failure("服务已断开", recoverable = true)
            }
            dispatched += step
            return ActionResult.Success
        }

        override fun cancelPendingGestures() {
            cancellations++
        }
    }

    private val script = ScriptModel(flow = listOf(TapStep(1f, 1f), TapStep(2f, 2f)))

    @Test
    fun runsTheFlowOnlyOnceInSingleMode() = runTest {
        val controller = FakeController()
        val executor = AutoRunnerScriptExecutor(controller, clock = VirtualExecutionClock())

        val report = executor.execute(script, ExecutionConfig(mode = ExecutionMode.ONCE))

        assertEquals(ExecutionOutcome.COMPLETED, report.outcome)
        assertEquals(1, report.completedLoops)
        assertEquals(2, report.executedActions)
        assertEquals(ExecutionState.COMPLETED, executor.state.value)
    }

    @Test
    fun repeatsTheFlowAndHonoursTheInterval() = runTest {
        val clock = VirtualExecutionClock()
        val controller = FakeController()
        val executor = AutoRunnerScriptExecutor(controller, clock = clock)

        val report = executor.execute(
            script,
            ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 4, intervalMs = 2_000),
        )

        assertEquals(4, report.completedLoops)
        assertEquals(8, controller.dispatched.size)
        // 3 intervals of 2s plus the per action delays (2 taps x 300ms x 4 loops)
        assertEquals(6_000L + 2_400L, report.elapsedMs)
    }

    @Test
    fun infiniteRepeatRunsUntilStopped() = runTest {
        val controller = FakeController()
        val executor = AutoRunnerScriptExecutor(controller, clock = VirtualExecutionClock())

        val job = launch {
            executor.execute(script, ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 0))
        }

        // Let a few loops happen, then stop.
        repeat(6) { yield() }
        executor.stop()
        job.join()

        assertEquals(ExecutionState.STOPPED, executor.state.value)
        assertTrue(controller.cancellations > 0)
    }

    @Test
    fun pauseParksTheRunUntilResumed() = runTest {
        val controller = FakeController()
        val clock = VirtualExecutionClock()
        val executor = AutoRunnerScriptExecutor(controller, clock = clock)

        val job = launch {
            executor.execute(script, ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 50))
        }

        yield()
        executor.pause()
        assertEquals(ExecutionState.PAUSED, executor.state.value)

        val dispatchedWhilePaused = controller.dispatched.size
        repeat(20) { yield() }
        assertEquals(dispatchedWhilePaused, controller.dispatched.size, "no action may run while paused")

        executor.resume()
        executor.stop()
        job.join()

        assertEquals(ExecutionState.STOPPED, executor.state.value)
        assertEquals(ExecutionState.STOPPED, executor.progress.value.state)
    }

    @Test
    fun recoverableFailuresRetryTheSameActionAfterTheServiceReturns() = runTest {
        val controller = FakeController(isConnected = false)
        val clock = VirtualExecutionClock()
        val executor = AutoRunnerScriptExecutor(controller, clock = clock)

        val job = launch {
            executor.execute(
                script,
                ExecutionConfig(mode = ExecutionMode.ONCE, reconnectTimeoutMs = 2_000),
            )
        }

        yield()
        controller.isConnected = true
        job.join()

        // Both actions were retried successfully after the service came back.
        assertEquals(2, controller.dispatched.size)
        assertEquals(ExecutionState.COMPLETED, executor.state.value)
        assertEquals(2, executor.progress.value.totalActions)
    }

    @Test
    fun abortStrategyStopsTheRunWhenAnActionCannotBeDispatched() = runTest {
        val controller = FakeController(isConnected = false)
        val executor = AutoRunnerScriptExecutor(controller, clock = VirtualExecutionClock())

        val report = executor.execute(
            script,
            ExecutionConfig(
                mode = ExecutionMode.ONCE,
                failureStrategy = FailureStrategy.ABORT_SCRIPT,
                reconnectTimeoutMs = 0,
            ),
        )

        assertEquals(ExecutionOutcome.FAILED, report.outcome)
        assertEquals("无障碍服务未连接", report.failureMessage)
        assertEquals(0, report.completedLoops)
    }

    @Test
    fun skipStrategyKeepsGoingAndCountsTheSkips() = runTest {
        val controller = FakeController(isConnected = false)
        val executor = AutoRunnerScriptExecutor(controller, clock = VirtualExecutionClock())

        val report = executor.execute(
            script,
            ExecutionConfig(
                mode = ExecutionMode.ONCE,
                failureStrategy = FailureStrategy.SKIP_ACTION,
                reconnectTimeoutMs = 0,
            ),
        )

        assertEquals(ExecutionOutcome.COMPLETED, report.outcome)
        assertEquals(2, report.skippedActions)
        assertEquals(0, report.executedActions)
    }

    @Test
    fun emptyScriptsFailFast() = runTest {
        val executor = AutoRunnerScriptExecutor(FakeController(), clock = VirtualExecutionClock())

        val report = executor.execute(ScriptModel(), ExecutionConfig())

        assertEquals(ExecutionOutcome.FAILED, report.outcome)
        assertTrue(report.failureMessage!!.contains("不包含任何动作"))
    }

    @Test
    fun refusesToStartTwice() = runTest {
        val executor = AutoRunnerScriptExecutor(FakeController(), clock = VirtualExecutionClock())
        val job = launch {
            executor.execute(script, ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 100))
        }
        yield()

        val second = executor.execute(script, ExecutionConfig())

        assertEquals(ExecutionOutcome.FAILED, second.outcome)
        assertEquals("已有任务正在执行", second.failureMessage)
        executor.stop()
        job.join()
    }

    @Test
    fun gamepadStepsGoToTheGateway() = runTest {
        val controller = FakeController()
        var pressed: GamepadButton? = null
        val gateway = object : GamepadGateway {
            override val isSupported = true
            override val isConnected = true
            override suspend fun connect() = true
            override fun disconnect() = Unit
            override suspend fun execute(step: GamepadStep, mode: GamepadMode) {
                pressed = step.button
            }
        }
        val executor = AutoRunnerScriptExecutor(controller, gateway, VirtualExecutionClock())

        val report = executor.execute(
            ScriptModel(flow = listOf(GamepadStep(button = GamepadButton.B, action = GamepadAction.CLICK))),
            ExecutionConfig(),
        )

        assertEquals(ExecutionOutcome.COMPLETED, report.outcome)
        assertEquals(GamepadButton.B, pressed)
        // The gamepad action must not reach the accessibility service.
        assertTrue(controller.dispatched.isEmpty())
    }

    @Test
    fun progressIsReportedForEveryActionAndLoop() = runTest {
        val executor = AutoRunnerScriptExecutor(FakeController(), clock = VirtualExecutionClock())
        val snapshots = mutableListOf<String>()

        executor.execute(
            script,
            ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 2),
        ) { progress ->
            snapshots += "${progress.completedLoops}:${progress.currentActionIndex}"
        }

        assertEquals(listOf("0:0", "0:1", "1:-1", "1:0", "1:1", "2:-1", "2:-1"), snapshots)
        assertFalse(executor.isActive)
    }
}
