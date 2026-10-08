package com.autorunner.core.di

import com.autorunner.core.execution.AutoRunnerScriptExecutor
import com.autorunner.core.execution.ExecutionController
import com.autorunner.core.model.AppSettings
import com.autorunner.core.model.GamepadGateway
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.GamepadCalibrationController
import com.autorunner.core.platform.OverlayManager
import com.autorunner.core.platform.RootInputBackend
import com.autorunner.core.platform.RecordingController
import com.autorunner.core.platform.createAccessibilityController
import com.autorunner.core.platform.createGamepadCalibrationController
import com.autorunner.core.platform.createOverlayManager
import com.autorunner.core.platform.createRecordingController
import com.autorunner.core.script.FileScriptRepository
import com.autorunner.core.script.ScriptRepository
import com.autorunner.core.serialization.ArScriptCodec
import com.autorunner.core.settings.SettingsRepository
import com.autorunner.core.storage.KeyValueStore
import com.autorunner.core.storage.ScriptStorage
import com.autorunner.core.storage.createKeyValueStore
import com.autorunner.core.storage.createScriptStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Hand rolled dependency container (no DI framework needed for a graph this
 * small, see §8.3 of the design document).
 *
 * The platform layer creates the services first (`AutoRunnerAccessibilityService`,
 * `AutoRunnerOverlayService`, …) and publishes them through
 * `PlatformServices`; [AutoRunnerCore.createContainer] then wires everything
 * together for the shared UI.
 */
class AppContainer(
    val codec: ArScriptCodec,
    val scriptRepository: ScriptRepository,
    val settingsRepository: SettingsRepository,
    /** 通用键值存储；除设置外的一次性标记（如示例脚本是否已导入）也放在这里。 */
    val keyValueStore: KeyValueStore,
    val accessibilityController: AccessibilityController,
    val recordingController: RecordingController,
    val overlayManager: OverlayManager,
    val gamepadCalibrationController: GamepadCalibrationController,
    val executor: AutoRunnerScriptExecutor,
    val executionController: ExecutionController,
    val gamepadGateway: GamepadGateway?,
    val scope: CoroutineScope,
    /** Root 注入后端（模块未安装时为 null）。 */
    val rootInputBackend: RootInputBackend? = null,
) {

    /** Latest settings snapshot, convenience for the UI layer. */
    val settings: StateFlow<AppSettings> get() = settingsRepository.settings
}

/** Factory for the shared object graph. */
object AutoRunnerCore {

    /**
     * Builds the container.
     *
     * The three platform controllers can be supplied explicitly. That matters on
     * Android: `expect` factories fall back to no-op implementations until the
     * platform layer has registered its services, so a container built too early
     * would capture the no-op controllers and every feature would report "service
     * not connected" for the whole process lifetime.
     *
     * @param scope lifetime scope of the application; the execution job runs in it.
     * @param gamepadGateway optional local gamepad injector.
     * @param accessibilityController overrides the `expect` factory when non-null.
     * @param recordingController overrides the `expect` factory when non-null.
     * @param overlayManager overrides the `expect` factory when non-null.
     * @param gamepadCalibrationController overrides the `expect` factory when non-null.
     */
    fun createContainer(
        scope: CoroutineScope,
        scriptStorage: ScriptStorage = createScriptStorage(),
        keyValueStore: KeyValueStore = createKeyValueStore(),
        gamepadGateway: GamepadGateway? = null,
        repositoryDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
        accessibilityController: AccessibilityController? = null,
        recordingController: RecordingController? = null,
        overlayManager: OverlayManager? = null,
        gamepadCalibrationController: GamepadCalibrationController? = null,
        rootInputBackend: RootInputBackend? = null,
    ): AppContainer {
        val resolvedAccessibility = accessibilityController ?: createAccessibilityController()
        val resolvedRecording = recordingController ?: createRecordingController()
        val resolvedOverlay = overlayManager ?: createOverlayManager()
        val resolvedGamepadCalibration = gamepadCalibrationController ?: createGamepadCalibrationController()
        val codec = ArScriptCodec()
        val settingsRepository = SettingsRepository(keyValueStore)
        val scriptRepository = FileScriptRepository(
            storage = scriptStorage,
            codec = codec,
            metricsProvider = { resolvedAccessibility.screenMetrics },
            dispatcher = repositoryDispatcher,
        )
        val executor = AutoRunnerScriptExecutor(
            accessibilityController = resolvedAccessibility,
            gamepadGateway = gamepadGateway,
            rootBackend = rootInputBackend,
            inputMode = { settingsRepository.current.inputMode },
        )
        val executionController = ExecutionController(executor, scope)

        return AppContainer(
            codec = codec,
            scriptRepository = scriptRepository,
            settingsRepository = settingsRepository,
            keyValueStore = keyValueStore,
            accessibilityController = resolvedAccessibility,
            recordingController = resolvedRecording,
            overlayManager = resolvedOverlay,
            gamepadCalibrationController = resolvedGamepadCalibration,
            executor = executor,
            executionController = executionController,
            gamepadGateway = gamepadGateway,
            rootInputBackend = rootInputBackend,
            scope = scope,
        )
    }

    /** Creates a scope that keeps the graph alive for the process lifetime. */
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob())
}
