package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.di.AutoRunnerCore
import com.autorunner.core.platform.ActionResult
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.CoordinateSpace
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.TapStep
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.PlatformServices
import com.autorunner.core.serialization.ArScriptCodec
import com.autorunner.core.storage.InMemoryKeyValueStore
import com.autorunner.core.storage.InMemoryScriptStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ViewModel level tests.
 *
 * The shared `PlatformServices` locator is what makes this possible without a
 * device: the tests publish a fake [AccessibilityController] and an in-memory
 * script store, then drive the same ViewModels the Compose screens use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {

    private class FakeAccessibilityController(
        override var isConnected: Boolean = true,
    ) : AccessibilityController {
        val dispatched = mutableListOf<ActionStep>()

        override val supportsGestures: Boolean = true

        override val screenMetrics: ScreenMetrics = ScreenMetrics(1080, 2400, 2.75f)

        override fun refreshScreenMetrics(): ScreenMetrics = screenMetrics

        override suspend fun perform(step: ActionStep): ActionResult {
            if (!isConnected) return ActionResult.Failure("无障碍服务未连接", recoverable = true)
            dispatched += step
            return ActionResult.Success
        }

        override fun cancelPendingGestures() = Unit
    }

    /**
     * Builds a container whose scope runs eagerly on the test scheduler.
     *
     * The ViewModels keep long lived collectors alive for the lifetime of the
     * app, so the container gets a scope of its own rather than the test job
     * (which would never become idle).
     */
    private fun TestScope.container(controller: AccessibilityController): AppContainer {
        PlatformServices.reset()
        PlatformServices.accessibilityController = controller
        val container = AutoRunnerCore.createContainer(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            scriptStorage = InMemoryScriptStorage(),
            keyValueStore = InMemoryKeyValueStore(),
            // Unconfined keeps the repository IO on the test scheduler.
            repositoryDispatcher = Dispatchers.Unconfined,
        )
        PlatformServices.reset()
        return container
    }

    @AfterTest
    fun tearDown() = PlatformServices.reset()

    // ------------------------------------------------------------------ editor

    @Test
    fun editorBuildsAndStoresAScript() = runTest {
        val container = container(FakeAccessibilityController())
        val editor = ScriptEditorViewModel(container)

        editor.load(null)
        editor.setName("登录流程")
        editor.addStep(TapStep(100f, 200f))
        editor.addStep(TapStep(300f, 400f))
        editor.setRepeatCount(5)
        advanceUntilIdle()

        assertEquals(2, editor.flow.value.size)
        assertEquals(ExecutionMode.REPEAT, editor.execution.value.mode)
        assertEquals(5, editor.execution.value.repeatCount)
        assertTrue(editor.dirty.value)
        assertTrue(editor.validation.value.isValid)

        var savedId: String? = null
        editor.save { savedId = it.id }
        advanceUntilIdle()

        val record = container.scriptRepository.load(savedId!!)
        assertNotNull(record)
        assertEquals("登录流程", record.name)
        assertEquals(2, record.stepCount)
        assertFalse(editor.dirty.value)

        // The device block comes from the accessibility controller's metrics.
        assertEquals(1080, record.script.info.device.width)
        assertEquals(2400, record.script.info.device.height)
    }

    @Test
    fun editorReordersDuplicatesAndRemovesSteps() = runTest {
        val container = container(FakeAccessibilityController())
        val editor = ScriptEditorViewModel(container)
        editor.load(null)
        editor.addStep(TapStep(1f, 1f))
        editor.addStep(TapStep(2f, 2f))
        editor.addStep(TapStep(3f, 3f))

        editor.moveStep(2, 0)
        assertEquals(listOf(3f, 1f, 2f), editor.flow.value.map { (it as TapStep).x })

        editor.duplicateStep(0)
        assertEquals(4, editor.flow.value.size)

        editor.removeStep(0)
        assertEquals(3, editor.flow.value.size)
        assertEquals(listOf(3f, 1f, 2f), editor.flow.value.map { (it as TapStep).x })
    }

    @Test
    fun editorConvertsBetweenAbsoluteAndNormalisedCoordinates() = runTest {
        val container = container(FakeAccessibilityController())
        val editor = ScriptEditorViewModel(container)
        editor.load(null)
        editor.addStep(TapStep(540f, 1200f))

        editor.toggleCoordinateSpace()
        assertEquals(CoordinateSpace.NORMALIZED, editor.coordinateSpace.value)
        val normalised = editor.currentScript.flow.single() as TapStep
        assertEquals(0.5f, normalised.x)
        assertEquals(0.5f, normalised.y)

        editor.toggleCoordinateSpace()
        assertEquals(CoordinateSpace.ABSOLUTE, editor.coordinateSpace.value)
    }

    @Test
    fun editorReportsValidationErrorsInline() = runTest {
        val container = container(FakeAccessibilityController())
        val editor = ScriptEditorViewModel(container)
        editor.load(null)
        advanceUntilIdle()

        // No actions yet.
        assertFalse(editor.validation.value.isValid)
        assertTrue(editor.validation.value.errors.any { it.code == "empty_flow" })

        editor.addStep(TapStep(1f, 1f, duration = 0L))
        advanceUntilIdle()
        assertTrue(editor.validation.value.errors.any { it.code == "non_positive_duration" })
    }

    // --------------------------------------------------------------- execution

    @Test
    fun executionPanelRunsTheSelectedScriptToCompletion() = runTest {
        val controller = FakeAccessibilityController()
        val container = container(controller)
        val record = container.scriptRepository.save(
            null,
            ScriptModel(flow = listOf(TapStep(10f, 10f), TapStep(20f, 20f))),
        )
        advanceUntilIdle()

        val execution = ExecutionViewModel(container)
        execution.refreshScripts()
        advanceUntilIdle()
        execution.selectScript(record.id)
        advanceUntilIdle()

        execution.setRepeatCount(3)
        assertEquals(ExecutionMode.REPEAT, execution.mode.value)
        assertEquals(3, execution.buildConfig().repeatCount)

        execution.start(record.id)
        advanceUntilIdle()

        assertEquals(6, controller.dispatched.size)
        assertFalse(execution.isRunning)
        assertNotNull(execution.lastReport.value)
        assertEquals(3, execution.lastReport.value!!.completedLoops)
    }

    /**
     * Regression: the library's ▶ calls `start(id)` directly, so the script's own
     * `execution` block (here "repeat 20×") must be adopted — it used to be ignored
     * and the task ran exactly once.
     */
    @Test
    fun startingAScriptFromTheLibraryAdoptsItsStoredRepeatConfig() = runTest {
        val controller = FakeAccessibilityController()
        val container = container(controller)
        val record = container.scriptRepository.save(
            null,
            ScriptModel(
                execution = com.autorunner.core.model.ExecutionConfig(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = 20,
                    intervalMs = 1_000,
                ),
                flow = listOf(TapStep(1f, 1f)),
            ),
        )
        advanceUntilIdle()

        val execution = ExecutionViewModel(container)
        execution.refreshScripts()
        advanceUntilIdle()

        execution.start(record.id)
        advanceUntilIdle()

        val report = execution.lastReport.value
        assertNotNull(report)
        assertEquals(20, report.totalLoops)
        assertEquals(20, report.completedLoops)
        assertEquals(20, controller.dispatched.size)
    }

    @Test
    fun aPanelTweakIsNotOverwrittenWhenRestartingTheSameScript() = runTest {
        val controller = FakeAccessibilityController()
        val container = container(controller)
        val record = container.scriptRepository.save(
            null,
            ScriptModel(
                execution = com.autorunner.core.model.ExecutionConfig(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = 20,
                ),
                flow = listOf(TapStep(1f, 1f)),
            ),
        )
        advanceUntilIdle()

        val execution = ExecutionViewModel(container)
        execution.refreshScripts()
        advanceUntilIdle()
        execution.selectScript(record.id)
        advanceUntilIdle()

        // The user narrows the run down to 3 loops in the panel.
        execution.setRepeatCount(3)
        execution.start(record.id)
        advanceUntilIdle()

        assertEquals(3, execution.lastReport.value?.totalLoops)
    }

    /** A run that starts must not interrupt the user with a dialog. */
    @Test
    fun startingARunDoesNotShowADialog() = runTest {
        val controller = FakeAccessibilityController()
        val container = container(controller)
        val record = container.scriptRepository.save(
            null,
            ScriptModel(
                execution = com.autorunner.core.model.ExecutionConfig(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = 5,
                    intervalMs = 10_000,
                ),
                flow = listOf(TapStep(1f, 1f)),
            ),
        )
        advanceUntilIdle()

        val execution = ExecutionViewModel(container)
        execution.refreshScripts()
        advanceUntilIdle()

        execution.start(record.id)
        runCurrent()
        assertNull(execution.message.value, "starting a run must not pop a dialog")
        assertTrue(execution.isRunning)

        execution.stop()
        advanceUntilIdle()
    }

    @Test
    fun executionRefusesToRunWithoutTheAccessibilityService() = runTest {
        val controller = FakeAccessibilityController(isConnected = false)
        val container = container(controller)
        val record = container.scriptRepository.save(null, ScriptModel(flow = listOf(TapStep(1f, 1f))))
        advanceUntilIdle()

        val execution = ExecutionViewModel(container)
        execution.refreshScripts()
        advanceUntilIdle()
        execution.start(record.id)
        advanceUntilIdle()

        assertEquals("无障碍服务未连接，无法执行", execution.message.value)
        assertTrue(controller.dispatched.isEmpty())
    }

    @Test
    fun executionAdoptsTheScriptStoredConfiguration() = runTest {
        val container = container(FakeAccessibilityController())
        val record = container.scriptRepository.save(
            null,
            ScriptModel(
                execution = com.autorunner.core.model.ExecutionConfig(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = 12,
                    intervalMs = 500,
                ),
                flow = listOf(TapStep(1f, 1f)),
            ),
        )
        advanceUntilIdle()

        val execution = ExecutionViewModel(container)
        execution.refreshScripts()
        advanceUntilIdle()
        execution.selectScript(record.id)
        advanceUntilIdle()

        assertEquals(ExecutionMode.REPEAT, execution.mode.value)
        assertEquals(12, execution.repeatCount.value)
        assertEquals(500L, execution.intervalMs.value)
    }

    // ------------------------------------------------------------- script list

    @Test
    fun listFiltersImportsAndDeletes() = runTest {
        val container = container(FakeAccessibilityController())
        container.scriptRepository.save(
            null,
            ScriptModel(info = com.autorunner.core.model.ScriptInfo(name = "签到"), flow = listOf(TapStep(1f, 1f))),
        )
        container.scriptRepository.save(
            null,
            ScriptModel(info = com.autorunner.core.model.ScriptInfo(name = "刷视频"), flow = listOf(TapStep(2f, 2f))),
        )
        advanceUntilIdle()

        val list = ScriptListViewModel(container)
        list.refresh()
        advanceUntilIdle()
        assertEquals(2, list.visibleScripts.value.size)

        list.setQuery("签到")
        assertEquals(1, list.visibleScripts.value.size)
        assertEquals("签到", list.visibleScripts.value.single().name)

        list.setQuery("")

        // Import a payload produced by the codec.
        val payload = ArScriptCodec().encode(
            ScriptModel(
                info = com.autorunner.core.model.ScriptInfo(name = "导入脚本"),
                flow = listOf(TapStep(3f, 3f)),
            ),
        )
        list.importText(payload, "imported.arscript")
        advanceUntilIdle()
        assertEquals(3, list.visibleScripts.value.size)

        list.delete(list.visibleScripts.value.first { it.name == "签到" }.id)
        advanceUntilIdle()
        assertEquals(2, list.visibleScripts.value.size)
    }

    // --------------------------------------------------------------- settings

    @Test
    fun settingsWriteThroughToTheRepository() = runTest {
        val container = container(FakeAccessibilityController())
        val settings = SettingsViewModel(container)

        settings.setThemeMode(com.autorunner.core.model.ThemeMode.DARK)
        settings.setDefaultRepeatCount(7)
        settings.setDefaultIntervalMs(1500)
        settings.setFailureStrategy(com.autorunner.core.model.FailureStrategy.SKIP_ACTION)
        settings.setKeepScreenOn(false)
        settings.setCaptureMultiTouch(false)
        advanceUntilIdle()

        val stored = container.settingsRepository.current
        assertEquals(com.autorunner.core.model.ThemeMode.DARK, stored.themeMode)
        assertEquals(7, stored.defaultExecution.repeatCount)
        assertEquals(1500L, stored.defaultExecution.intervalMs)
        assertEquals(com.autorunner.core.model.FailureStrategy.SKIP_ACTION, stored.failureStrategy)
        // The policy is mirrored into the default execution block as well.
        assertEquals(
            com.autorunner.core.model.FailureStrategy.SKIP_ACTION,
            stored.defaultExecution.failureStrategy,
        )
        assertFalse(stored.keepScreenOnWhileRunning)
        assertFalse(stored.recording.captureMultiTouch)

        settings.resetSettings()
        advanceUntilIdle()
        assertEquals(com.autorunner.core.model.AppSettings.Default, container.settingsRepository.current)
    }
}
