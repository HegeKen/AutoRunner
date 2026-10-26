package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.di.AutoRunnerCore
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptInfo
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.TapStep
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.ActionResult
import com.autorunner.core.platform.OverlayManager
import com.autorunner.core.platform.OverlayState
import com.autorunner.core.platform.PlatformServices
import com.autorunner.core.settings.ComboStore
import com.autorunner.core.storage.InMemoryKeyValueStore
import com.autorunner.core.storage.InMemoryScriptStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComboViewModelTest {

    private class FakeAccessibilityController : AccessibilityController {
        val dispatched = mutableListOf<ActionStep>()

        override val isConnected: Boolean = true
        override val supportsGestures: Boolean = true
        override val screenMetrics: ScreenMetrics = ScreenMetrics(1080, 2400, 2.75f)

        override fun refreshScreenMetrics(): ScreenMetrics = screenMetrics

        override suspend fun perform(step: ActionStep): ActionResult {
            dispatched += step
            return ActionResult.Success
        }

        override fun cancelPendingGestures() = Unit
    }

    /** 记录武装请求的悬浮层；测试不启动真实 Android 服务。 */
    private class FakeOverlayManager : OverlayManager {
        private val _state = MutableStateFlow(OverlayState(permissionGranted = true))
        override val state: StateFlow<OverlayState> = _state

        var armedCombo: com.autorunner.core.model.ComboConfig? = null
        var wentHome: Boolean = false

        override fun isPermissionGranted(): Boolean = true

        override fun show(): Boolean = true

        override fun armExecution(scriptId: String): Boolean = true

        override fun armCombo(combo: com.autorunner.core.model.ComboConfig): Boolean {
            armedCombo = combo
            return true
        }

        override fun goHome() {
            wentHome = true
        }

        override fun hide() = Unit
        override fun toggle() = Unit
        override fun expand() = Unit
        override fun collapse() = Unit
        override fun refreshPermissionState() = Unit
    }

    private fun TestScope.container(
        access: AccessibilityController,
        overlay: OverlayManager,
        keyValueStore: InMemoryKeyValueStore,
    ): AppContainer {
        PlatformServices.reset()
        PlatformServices.accessibilityController = access
        val container = AutoRunnerCore.createContainer(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            scriptStorage = InMemoryScriptStorage(),
            keyValueStore = keyValueStore,
            repositoryDispatcher = Dispatchers.Unconfined,
            overlayManager = overlay,
        )
        PlatformServices.reset()
        return container
    }

    @AfterTest
    fun tearDown() = PlatformServices.reset()

    @Test
    fun selectingASingleRunScriptDefaultsToInfiniteLoopAndPersists() = runTest {
        val access = FakeAccessibilityController()
        val overlay = FakeOverlayManager()
        val kv = InMemoryKeyValueStore()
        val container = container(access, overlay, kv)
        val a = container.scriptRepository.save(
            null,
            ScriptModel(info = ScriptInfo(name = "签到"), flow = listOf(TapStep(1f, 1f))),
        )
        advanceUntilIdle()

        val combo = ComboViewModel(container)
        combo.selectScript(ComboSlotRole.A, a.id)
        advanceUntilIdle()

        assertEquals(a.id, combo.slotA.value.scriptId)
        assertEquals("签到", combo.slotA.value.scriptName)
        assertEquals(ExecutionMode.REPEAT, combo.slotA.value.execution.mode)
        assertEquals(0, combo.slotA.value.execution.repeatCount)

        // 保存的配置经 ComboStore 往返后仍是同样的参数。
        val restored = ComboStore(kv).load()
        assertEquals(a.id, restored.slotA.scriptId)
        assertEquals(ExecutionMode.REPEAT, restored.slotA.execution.mode)
    }

    @Test
    fun firstRoundDelayIsKeptOnSlotBAndClearedOnSlotA() = runTest {
        val access = FakeAccessibilityController()
        val overlay = FakeOverlayManager()
        val kv = InMemoryKeyValueStore()
        val container = container(access, overlay, kv)
        val a = container.scriptRepository.save(null, ScriptModel(flow = listOf(TapStep(1f, 1f))))
        val b = container.scriptRepository.save(null, ScriptModel(flow = listOf(TapStep(2f, 2f))))
        advanceUntilIdle()

        val combo = ComboViewModel(container)
        combo.selectScript(ComboSlotRole.A, a.id)
        combo.selectScript(ComboSlotRole.B, b.id)
        advanceUntilIdle()

        combo.setIntervalMs(ComboSlotRole.A, 14 * 60_000L)
        combo.setIntervalMs(ComboSlotRole.B, 50 * 60_000L)
        combo.setStartDelayMs(10 * 60_000L)
        advanceUntilIdle()

        val stored = ComboStore(kv).load()
        assertEquals(14 * 60_000L, stored.slotA.execution.intervalMs)
        assertEquals(50 * 60_000L, stored.slotB.execution.intervalMs)
        assertEquals(10 * 60_000L, stored.slotB.execution.startDelayMs)

        // 即便错误地给 A 写入延迟，持久化层也会归零。
        assertEquals(0L, combo.buildConfig().slotA.execution.startDelayMs)
    }

    @Test
    fun startArmsTheComboAndGoesHome() = runTest {
        val access = FakeAccessibilityController()
        val overlay = FakeOverlayManager()
        val kv = InMemoryKeyValueStore()
        val container = container(access, overlay, kv)
        val a = container.scriptRepository.save(null, ScriptModel(flow = listOf(TapStep(1f, 1f))))
        val b = container.scriptRepository.save(null, ScriptModel(flow = listOf(TapStep(2f, 2f))))
        advanceUntilIdle()

        val combo = ComboViewModel(container)
        combo.selectScript(ComboSlotRole.A, a.id)
        combo.selectScript(ComboSlotRole.B, b.id)
        combo.setStartDelayMs(1_000L)
        combo.start()
        advanceUntilIdle()

        assertTrue(overlay.wentHome, "开始组合后应回到桌面")
        val armed = assertNotNull(overlay.armedCombo)
        assertEquals(a.id, armed.slotA.scriptId)
        assertEquals(b.id, armed.slotB.scriptId)
        assertEquals(1_000L, armed.slotB.execution.startDelayMs)
        assertNull(combo.message.value)
    }

    @Test
    fun refusesToCombineTheSameScript() = runTest {
        val container = container(FakeAccessibilityController(), FakeOverlayManager(), InMemoryKeyValueStore())
        val a = container.scriptRepository.save(null, ScriptModel(flow = listOf(TapStep(1f, 1f))))
        advanceUntilIdle()

        val combo = ComboViewModel(container)
        combo.selectScript(ComboSlotRole.A, a.id)
        combo.selectScript(ComboSlotRole.B, a.id)
        combo.start()
        advanceUntilIdle()

        assertEquals("请选择两个不同的脚本", combo.message.value)
    }

    /**
     * 端到端：ComboViewModel 武装 → 悬浮球的 ExecutionViewModel 单击启动 →
     * 两段脚本并行执行完毕，控制器聚合成 COMPLETED。
     */
    @Test
    fun armedComboStartsBothScriptsFromTheFloatingBall() = runTest {
        val access = FakeAccessibilityController()
        val overlay = FakeOverlayManager()
        val container = container(access, overlay, InMemoryKeyValueStore())
        val a = container.scriptRepository.save(
            null,
            ScriptModel(info = ScriptInfo(name = "A"), flow = listOf(TapStep(1f, 1f))),
        )
        val b = container.scriptRepository.save(
            null,
            ScriptModel(info = ScriptInfo(name = "B"), flow = listOf(TapStep(2f, 2f))),
        )
        advanceUntilIdle()

        val combo = ComboViewModel(container)
        combo.selectScript(ComboSlotRole.A, a.id)
        combo.selectScript(ComboSlotRole.B, b.id)
        combo.setRepeatCount(ComboSlotRole.A, 1)
        combo.setRepeatCount(ComboSlotRole.B, 1)
        combo.setStartDelayMs(0L)
        combo.start()
        advanceUntilIdle()

        val armed = assertNotNull(overlay.armedCombo)
        // 模拟悬浮服务收到武装请求，随后用户单击悬浮球。
        val overlayViewModel = ExecutionViewModel(container)
        overlayViewModel.armCombo(armed)
        overlayViewModel.startFirstAvailable()
        advanceUntilIdle()

        assertEquals(ExecutionState.COMPLETED, overlayViewModel.state.value)
        assertEquals(2, access.dispatched.size)
        val progress = assertNotNull(container.executionController.comboProgress.value)
        assertEquals(ExecutionState.COMPLETED, progress.state)

        // 运行结束后新建 ViewModel（模拟用户从桌面回到应用）仍能读到汇总报告。
        val reopened = ComboViewModel(container)
        advanceUntilIdle()
        assertNotNull(reopened.lastReport.value)
        assertEquals(2, reopened.lastReport.value?.completedLoops)
    }
}
