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
 * 应用入口。
 *
 * 职责（见 §3.2 与 §8.3）：
 *
 * 1. 将 Android `Context` 发布到共享的平台层，
 * 2. 在设备支持时创建本地手柄注入器，
 * 3. 将平台服务（无障碍控制器、录制控制器、
 *    悬浮层管理器）注册进 `PlatformServices`，
 * 4. 构建 UI 与所有服务共用的唯一 [AppContainer]。
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
 * 进程级服务定位器。
 *
 * `AccessibilityService` 实例由系统创建，且服务会从多处启动（Activity、
 * 通知动作、开机启动等），因此这里用一个手写的小型依赖图比引入 DI 框架
 * 更容易推理。
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

    /** Activity 发布的文件传输控制器，供调试通道使用。 */
    @Volatile
    var transfer: com.autorunner.ui.platform.ScriptTransferController? = null

    /** 返回容器；若应用尚未构建则按需构建。 */
    fun requireContainer(): AppContainer {
        container?.let { return it }
        val application = application
            ?: error("AppGraph.initialize() must be called from Application.onCreate()")
        initialize(application)
        return container ?: error("AutoRunner container could not be created")
    }

    /**
     * 构建对象图；由 [AutoRunnerApplication] 调用一次。
     *
     * 顺序很重要：平台控制器必须**先于**共享容器创建，这样容器就不会捕获
     * `expect` 的空实现兜底。（若先构建容器，它会永远持有
     * `UnavailableAccessibilityController`，即便用户启用了无障碍服务，
     * 每个页面也会一直报“服务未连接”。）
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
                // 惰性解析：settings 仓库位于下方刚构建的
                // 容器内部。
                settingsProvider = { containerOrNull?.settingsRepository?.current ?: AppSettings.Default },
                accessibilityController = accessibility,
                // 录制期间透明采集层会吞掉所有触摸，
                // 所以用悬浮球来按“停止”。
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

            // --- 可选的手柄模块 ----------------------------------
            // 本地注入：手柄按键在*本*设备上变成触摸事件。
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

            // 先发布，让 `expect` 工厂的其他消费方拿到真实现，
            // 再把它们固定进容器。
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

            // 预热脚本缓存：悬浮服务、通知动作或调试命令
            // 可能在任何界面组合完成之前就用到执行器，
            // 而它们都会读取 `scriptRepository.scripts`。
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
