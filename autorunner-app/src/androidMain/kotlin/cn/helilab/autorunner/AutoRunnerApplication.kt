package cn.helilab.autorunner

import android.app.Application
import android.util.Log
import cn.helilab.autorunner.accessibility.AndroidAccessibilityController
import cn.helilab.autorunner.accessibility.AndroidRecordingController
import cn.helilab.autorunner.gamepad.AndroidGamepadStatusProvider
import cn.helilab.autorunner.input.AndroidRootInputBackend
import cn.helilab.autorunner.overlay.AndroidGamepadCalibrationController
import cn.helilab.autorunner.overlay.AndroidOverlayManager
import cn.helilab.autorunner.overlay.PendingOverlayAction
import com.autorunner.core.di.AppContainer
import com.autorunner.core.di.AutoRunnerCore
import com.autorunner.core.model.AppSettings
import com.autorunner.core.platform.AndroidPlatform
import com.autorunner.gamepad.LocalGamepadGateway
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Responsibilities (see §3.2 and §8.3):
 *
 * 1. publish the Android `Context` to the shared platform layer,
 * 2. create the local gamepad injector when the device supports it,
 * 3. register the platform services (accessibility controller, recording
 *    controller, overlay manager) into `PlatformServices`,
 * 4. build the single [AppContainer] used by the UI and every service.
 */
class AutoRunnerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.attach(this)
        AppGraph.initialize(this)
        Log.i(TAG, "AutoRunner initialised, container=${AppGraph.containerOrNull != null}")
    }

    companion object {
        const val TAG = "AutoRunner"
    }
}

/**
 * Process wide service locator.
 *
 * `AccessibilityService` instances are created by the system and services are
 * started from many places (activity, notification action, boot, …), so a tiny
 * hand written graph is easier to reason about than a DI framework here.
 */
object AppGraph {

    @Volatile
    private var container: AppContainer? = null

    @Volatile
    private var application: Application? = null

    @Volatile
    private var gamepadGateway: LocalGamepadGateway? = null

    @Volatile
    private var gamepadStatus: AndroidGamepadStatusProvider? = null

    val containerOrNull: AppContainer? get() = container

    val gamepad: AndroidGamepadStatusProvider? get() = gamepadStatus

    /** Transfer controller published by the activity, used by the debug channel. */
    @Volatile
    var transfer: com.autorunner.ui.platform.ScriptTransferController? = null

    /** The container, building it on demand if the application has not yet. */
    fun requireContainer(): AppContainer {
        container?.let { return it }
        val application = application
            ?: error("AppGraph.initialize() must be called from Application.onCreate()")
        initialize(application)
        return container ?: error("AutoRunner container could not be created")
    }

    /**
     * Builds the object graph; called once from [AutoRunnerApplication].
     *
     * Order matters: the platform controllers are created **before** the shared
     * container so the container never captures the no-op `expect` fallbacks.
     * (A container built first would hold `UnavailableAccessibilityController`
     * forever, and every screen would keep reporting "service not connected"
     * even after the user enabled the accessibility service.)
     */
    fun initialize(application: Application) {
        synchronized(this) {
            if (container != null) return
            this.application = application

            // --- platform services ------------------------------------------
            val accessibility = AndroidAccessibilityController(application)
            val overlay = AndroidOverlayManager(application)
            val gamepadCalibration = AndroidGamepadCalibrationController(application)
            val recording = AndroidRecordingController(
                context = application,
                // Resolved lazily: the settings repository lives inside the
                // container that is built right below.
                settingsProvider = { containerOrNull?.settingsRepository?.current ?: AppSettings.Default },
                accessibilityController = accessibility,
                // While recording, the transparent capture layer consumes every
                // touch, so the floating ball is the way to press "stop".
                ensureStopControl = {
                    val granted = overlay.isPermissionGranted()
                    if (granted) {
                        // 通知悬浮服务进入"录制待开始"，并以悬浮球形式显示；
                        // 用户点悬浮球开始/停止，不需要展开面板。
                        PendingOverlayAction.request(PendingOverlayAction.Mode.RECORD)
                        overlay.show()
                    }
                    granted
                },
            )

            // --- optional gamepad module -----------------------------------
            // Local injection: gamepad buttons become touches on *this* device.
            gamepadGateway = runCatching {
                val gateway = LocalGamepadGateway(
                    accessibilityController = accessibility,
                    settingsProvider = { containerOrNull?.settingsRepository?.current ?: AppSettings.Default },
                )
                gamepadStatus = AndroidGamepadStatusProvider(gateway, accessibility)
                gateway
            }.getOrElse { error ->
                Log.w(AutoRunnerApplication.TAG, "gamepad module unavailable: ${error.message}")
                null
            }

            // Publish first so any other consumer of the `expect` factories gets
            // the real implementations, then pin them into the container.
            AndroidPlatform.registerAccessibilityController(accessibility)
            AndroidPlatform.registerRecordingController(recording)
            AndroidPlatform.registerOverlayManager(overlay)
            AndroidPlatform.registerGamepadCalibrationController(gamepadCalibration)

            // Root 注入后端：装了模块走文件桥，否则用 su 直连；开关打开时可
            // 把内置模块导出到下载目录供用户安装。
            val rootInput = AndroidRootInputBackend(
                context = application,
                metrics = {
                    val metrics = application.resources.displayMetrics
                    metrics.widthPixels to metrics.heightPixels
                },
                normalised = {
                    containerOrNull?.settingsRepository?.current?.recording?.normaliseCoordinates
                        ?: AppSettings.Default.recording.normaliseCoordinates
                },
            )

            val built = AutoRunnerCore.createContainer(
                scope = AutoRunnerCore.applicationScope(),
                gamepadGateway = gamepadGateway,
                accessibilityController = accessibility,
                recordingController = recording,
                overlayManager = overlay,
                gamepadCalibrationController = gamepadCalibration,
                rootInputBackend = rootInput,
            )
            container = built

            // Warm the script cache: the overlay service, a notification action
            // or a debug command can reach the executor before any screen is
            // composed, and they all read `scriptRepository.scripts`.
            built.scope.launch { runCatching { built.scriptRepository.refresh() } }

            // 首次启动导入内置示例脚本，让用户装上就有可直接运行的样例。
            seedSampleScripts(application, built)

            Log.i(
                AutoRunnerApplication.TAG,
                "graph ready: accessibility=${accessibility.javaClass.simpleName}, " +
                    "recording=${recording.javaClass.simpleName}, " +
                    "overlay=${overlay.javaClass.simpleName}, " +
                    "gamepad=${gamepadGateway?.javaClass?.simpleName ?: "none"}",
            )
        }
    }

}
