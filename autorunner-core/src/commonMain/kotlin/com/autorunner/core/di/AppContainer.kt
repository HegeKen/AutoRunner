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
 * 手写的依赖容器（依赖图这么小，无需 DI 框架，见设计文档 §8.3）。
 *
 * 平台层先创建各服务（`AutoRunnerAccessibilityService`、
 * `AutoRunnerOverlayService` 等）并通过 `PlatformServices` 发布；
 * [AutoRunnerCore.createContainer] 随后把所有东西为共享 UI 装配到一起。
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

    /** 最新的设置快照，方便 UI 层使用。 */
    val settings: StateFlow<AppSettings> get() = settingsRepository.settings
}

/** 共享对象图的工厂。 */
object AutoRunnerCore {

    /**
     * 构建容器。
     *
     * 三个平台控制器可以显式传入。这在 Android 上很重要：`expect` 工厂
     * 在平台层注册服务之前会退化为 no-op 实现，若容器构建过早，
     * 就会捕获这些 no-op 控制器，导致所有功能在整个进程生命周期内
     * 一直报告“服务未连接”。
     *
     * @param scope 应用的生命周期 scope；执行任务在其中运行。
     * @param gamepadGateway 可选的本地手柄注入器。
     * @param accessibilityController 非空时覆盖 `expect` 工厂。
     * @param recordingController 非空时覆盖 `expect` 工厂。
     * @param overlayManager 非空时覆盖 `expect` 工厂。
     * @param gamepadCalibrationController 非空时覆盖 `expect` 工厂。
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

    /** 创建一个让对象图在进程生命周期内存活的 scope。 */
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob())
}
