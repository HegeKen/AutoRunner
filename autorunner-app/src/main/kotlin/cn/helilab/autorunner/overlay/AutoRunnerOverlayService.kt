package cn.helilab.autorunner.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import cn.helilab.autorunner.AppGraph
import cn.helilab.autorunner.AutoRunnerApplication
import cn.helilab.autorunner.accessibility.AccessibilityServiceHolder
import cn.helilab.autorunner.notification.ExecutionNotifications
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.platform.DockEdge
import com.autorunner.core.platform.RecordingStatus
import com.autorunner.ui.overlay.FloatingControlPanel
import com.autorunner.ui.theme.AutoRunnerTheme
import com.autorunner.ui.viewmodel.ExecutionViewModel
import com.autorunner.ui.viewmodel.RecordingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf

/**
 * 持有悬浮控制面板的前台服务（§6.4）。
 *
 * * 以固定频道 `autorunner_execution` 运行于前台，既满足“5 秒内 startForeground”
 *   的规则，又能在长时间循环运行期间保持进程（进而保持无障碍服务）存活；
 * * 在 `WindowManager` 悬浮层内渲染 [FloatingControlPanel]；
 * * 把执行进度连同暂停 / 停止操作同步到通知栏（§6.3.3）。
 */
class AutoRunnerOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var host: ComposeWindowHost? = null

    private var progressJob: Job? = null

    private var recordingJob: Job? = null

    private var pendingJob: Job? = null

    private lateinit var executionViewModel: ExecutionViewModel

    private lateinit var recordingViewModel: RecordingViewModel

    private var toastNotifier: ExecutionToastNotifier? = null

    /** 屏幕方向：横屏时悬浮球菜单排成一行，竖屏排成一列。在 [onCreate] 中初始化。 */
    private val isLandscape = mutableStateOf(false)

    /** 黑屏挂机覆盖层是否正在显示（驱动菜单图标重组）。 */
    private val blackScreen = mutableStateOf(false)

    private var idleScreenOverlay: IdleScreenOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape.value = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 屏幕宽高已变化，让悬浮窗按新尺寸重算位置，避免球留在屏幕外。
        host?.onConfigurationChanged()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 属性初始化阶段 base context 尚未附加，resources 会为 null，故在此读取方向。
        isLandscape.value =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        _isRunning.value = true
        OverlayStateHolder.update { it.copy(visible = true, error = null, permissionGranted = true) }

        val container = AppGraph.requireContainer()
        executionViewModel = ExecutionViewModel(container)
        recordingViewModel = RecordingViewModel(container)

        toastNotifier = ExecutionToastNotifier(
            context = this,
            isReminderEnabled = { container.settingsRepository.current.executionReminder },
            // 脚本卡片上的「调试」开关：开启的脚本运行时逐步提示步骤序号与内容。
            isScriptDebugEnabled = { id ->
                id != null && id in container.settingsRepository.current.debugScriptIds
            },
        ).also { it.bind(scope, executionViewModel) }

        startForegroundSafely()
        attachOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ExecutionNotifications.ACTION_STOP -> {
                // 通知栏的"停止"同时兜住录制（录制中没有执行任务时会走到这里）
                executionViewModel.stop()
                if (recordingViewModel.isRecording) recordingViewModel.stop()
            }
            ExecutionNotifications.ACTION_STOP_RECORDING -> recordingViewModel.stop()
            ExecutionNotifications.ACTION_TOGGLE_PAUSE -> executionViewModel.togglePause()
            ACTION_EXPAND -> host?.setMenuOpen(true)
            ACTION_COLLAPSE -> host?.setMenuOpen(false)
            ACTION_TOGGLE -> host?.toggleMenu()
            ACTION_SET_MODE -> {
                // HID 配置在注册时即已固定；这里除了更新持久化的偏好设置
                // （由设置页处理）之外无事可做。
            }
            ACTION_SET_BLACK_SCREEN ->
                setBlackScreenEnabled(intent.getBooleanExtra(EXTRA_BLACK_SCREEN, false))
        }
        OverlayStateHolder.update { it.copy(menuOpen = host?.menuOpen?.value ?: false, visible = true) }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _isRunning.value = false
        // 悬浮球被关闭：尚未启动的「待运行」一并作废，
        // 让无障碍服务停止监听键盘 / 鼠标输入。
        if (::executionViewModel.isInitialized) executionViewModel.disarm()
        progressJob?.cancel()
        recordingJob?.cancel()
        pendingJob?.cancel()
        idleScreenOverlay?.hide()
        idleScreenOverlay = null
        AccessibilityServiceHolder.current()?.hideIdleScreen()
        blackScreen.value = false
        host?.remove()
        host = null
        OverlayStateHolder.update { it.copy(visible = false) }
        ExecutionNotifications.cancel(this)
        scope.cancel()
        super.onDestroy()
    }

    // --------------------------------------------------------------- 悬浮层

    private fun attachOverlay() {
        val windowHost = ComposeWindowHost(
            context = this,
            onDockEdgeChanged = { edge -> OverlayStateHolder.update { it.copy(dockedEdge = edge) } },
        )
        host = windowHost
        windowHost.menuOpen.value = OverlayStateHolder.state.value.menuOpen
        windowHost.dockedEdge.value = OverlayStateHolder.state.value.dockedEdge

        val attached = windowHost.show {
            val menuOpenState = windowHost.menuOpen
            val dockedEdgeState = windowHost.dockedEdge
            // 响应式读取主题，App 内切换浅色/深色后悬浮窗立即生效。
            val settings by AppGraph.requireContainer().settingsRepository.settings.collectAsState()
            val state by executionViewModel.state.collectAsState()
            // 录制状态以 Compose state 驱动悬浮球重组（图标/强调色）。
            val recordingStatus by recordingViewModel.status.collectAsState()
            val recordingArmed by recordingViewModel.armed.collectAsState()
            val recording = recordingStatus == RecordingStatus.RECORDING

            AutoRunnerTheme(themeMode = settings.themeMode) {
                FloatingControlPanel(
                    state = state,
                    menuOpen = menuOpenState.value,
                    alignEnd = dockedEdgeState.value == DockEdge.RIGHT,
                    recording = recording,
                    recordingArmed = recordingArmed,
                    isLandscape = isLandscape.value,
                    blackScreen = blackScreen.value,
                    onToggleBlackScreen = { setBlackScreenEnabled(!blackScreen.value) },
                    onContentSizeChanged = { windowHost.setContentSize(it) },
                    onPrimaryAction = {
                        when {
                            // 录制中 → 停止录制
                            recordingViewModel.isRecording -> recordingViewModel.stop()
                            // 录制已就绪 → 开始采集
                            recordingViewModel.armed.value -> recordingViewModel.beginCapture()
                            // 执行中 / 已暂停 → 暂停 / 恢复
                            state.isActive -> executionViewModel.togglePause()
                            // 执行已完成 → 复位回空闲
                            state == ExecutionState.COMPLETED -> executionViewModel.reset()
                            // 兜底：无选中脚本时运行第一个可用脚本
                            else -> executionViewModel.startFirstAvailable()
                        }
                    },
                    onToggleMenu = {
                        windowHost.toggleMenu()
                        OverlayStateHolder.update { it.copy(menuOpen = windowHost.menuOpen.value) }
                    },
                    onStop = { executionViewModel.stop() },
                    onStopRecording = { recordingViewModel.stop() },
                    onTogglePause = { executionViewModel.togglePause() },
                    onClose = { stopSelf() },
                    dragModifier = windowHost.dragModifier(),
                )
            }
        }

        if (!attached) {
            Log.w(AutoRunnerApplication.TAG, "overlay window could not be attached")
            OverlayStateHolder.update { it.copy(visible = false, error = "悬浮窗创建失败，请检查悬浮窗权限") }
            stopSelf()
            return
        }

        observeProgress()
        observeRecording()
        observePending()
    }

    /**
     * 打开 / 关闭黑屏挂机层。
     *
     * 覆盖层让 display 保持 ON 但整屏纯黑，脚本注入的触摸因为
     * `FLAG_NOT_TOUCHABLE` 继续落到下层游戏，从而绕开「display OFF 时
     * InputDispatcher 丢弃注入触摸」的问题。层内文字缓慢反弹，避免烧屏。
     */
    private fun setBlackScreenEnabled(enabled: Boolean) {
        if (enabled == blackScreen.value) return
        if (enabled) {
            // 先收起菜单：洞口只需覆盖悬浮球，避免菜单里的操作被误触。
            host?.setMenuOpen(false)
            // 优先由无障碍服务添加 TYPE_ACCESSIBILITY_OVERLAY：MIUI 会把第三方
            // 全屏 TYPE_APPLICATION_OVERLAY 强制压到 80% 不透明度，无法纯黑。
            // 无障碍服务未启用时退回普通悬浮窗（会略微透出下层画面）。
            val accessibility = AccessibilityServiceHolder.current()
            val viaAccessibility = accessibility?.showIdleScreen() == true
            if (!viaAccessibility) {
                val overlay = IdleScreenOverlay(this)
                if (!overlay.show()) {
                    Log.w(AutoRunnerApplication.TAG, "black screen overlay could not be attached")
                    return
                }
                idleScreenOverlay = overlay
            }
            blackScreen.value = true
            // 回退路径用的是同层 APPLICATION_OVERLAY，重新添加才能盖住黑层；
            // 无障碍层永远在本层之上，无需（也无法）置顶。
            if (!viaAccessibility) host?.bringToFront()
        } else {
            AccessibilityServiceHolder.current()?.hideIdleScreen()
            idleScreenOverlay?.hide()
            idleScreenOverlay = null
            blackScreen.value = false
        }
    }

    /** 保持前台通知与执行器状态同步。 */
    private fun observeProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            executionViewModel.progress.collect { refreshNotification() }
        }
    }

    /**
     * 保持前台通知与录制状态同步。否则录制进行期间，“停止录制”操作永远不会
     * 出现在通知中。
     */
    private fun observeRecording() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            recordingViewModel.status.collect { refreshNotification() }
        }
    }

    /**
     * 接收 App 内 ViewModel 发来的一次性请求（“运行脚本 X” / “开始录制”），
     * 用以武装本服务自己的 ViewModel，随后收起菜单回到悬浮球，只需一次点击
     * 即可开始工作。
     */
    private fun observePending() {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            PendingOverlayAction.pending.collectLatest { pending ->
                if (pending == null) return@collectLatest
                when (pending.mode) {
                    PendingOverlayAction.Mode.RUN -> {
                        if (!pending.scriptId.isNullOrBlank()) {
                            executionViewModel.selectScript(pending.scriptId)
                        }
                        // 悬浮球已进入「待运行」：放行键盘 / 鼠标映射监听，
                        // 直到脚本启动（由运行状态接管）或悬浮球被关闭。
                        executionViewModel.markArmed()
                    }
                    PendingOverlayAction.Mode.RECORD -> {
                        recordingViewModel.markArmed()
                    }
                }
                host?.setMenuOpen(false)
                OverlayStateHolder.update { it.copy(menuOpen = false) }
                PendingOverlayAction.consume()
            }
        }
    }

    /**
     * 依据当前执行与录制状态重建前台通知，使通知上的操作始终与用户此刻能做
     * 的动作一致。
     */
    private fun refreshNotification() {
        if (!AppGraph.requireContainer().settingsRepository.current.executionNotification) return
        val progress = executionViewModel.progress.value
        val notification = when {
            progress.state.isActive -> ExecutionNotifications.build(this, progress)
            recordingViewModel.isRecording -> ExecutionNotifications.buildRecording(this)
            progress.state == ExecutionState.IDLE -> ExecutionNotifications.buildIdle(this)
            else -> ExecutionNotifications.build(this, progress)
        }
        runCatching {
            val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
            manager.notify(ExecutionNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundSafely() {
        val notification = ExecutionNotifications.buildIdle(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ExecutionNotifications.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(ExecutionNotifications.NOTIFICATION_ID, notification)
            }
        }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "startForeground failed", error)
            // 前台服务启动失败时系统稍后会抛异常杀掉本服务；
            // 与其让悬浮窗在"半死"状态下继续响应用户操作，不如立即自停。
            stopSelf()
        }
    }

    companion object {

        /**
         * 当前运行中的服务实例（仅主线程访问）。
         *
         * 无障碍服务收到「长按音量减键退出黑屏」时需要立即关闭黑屏层；
         * 直接调用已运行的实例可以避开 Android 12+ 的后台服务启动限制。
         */
        private var instance: AutoRunnerOverlayService? = null

        private val _isRunning = MutableStateFlow(false)

        /** 悬浮窗处于附着状态时为 `true`。 */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        const val ACTION_EXPAND = "cn.helilab.autorunner.action.EXPAND_OVERLAY"
        const val ACTION_COLLAPSE = "cn.helilab.autorunner.action.COLLAPSE_OVERLAY"
        const val ACTION_TOGGLE = "cn.helilab.autorunner.action.TOGGLE_OVERLAY"
        const val ACTION_SET_MODE = "cn.helilab.autorunner.action.SET_OVERLAY_MODE"
        const val ACTION_SET_BLACK_SCREEN = "cn.helilab.autorunner.action.SET_BLACK_SCREEN"
        private const val EXTRA_BLACK_SCREEN = "blackScreen"

        /** 启动（或重新下达指令给）悬浮层服务。 */
        fun start(context: Context, action: String? = null) {
            start(
                context,
                Intent(context, AutoRunnerOverlayService::class.java).apply {
                    if (action != null) this.action = action
                },
            )
        }

        /** 打开 / 关闭黑屏挂机层（调试与自动化用）。 */
        fun setBlackScreen(context: Context, enabled: Boolean) {
            start(
                context,
                Intent(context, AutoRunnerOverlayService::class.java).apply {
                    action = ACTION_SET_BLACK_SCREEN
                    putExtra(EXTRA_BLACK_SCREEN, enabled)
                },
            )
        }

        /** 由无障碍服务调用（长按音量减键）：立即关闭黑屏挂机层。 */
        fun requestExitBlackScreen(context: Context) {
            val running = instance
            if (running != null) {
                running.setBlackScreenEnabled(false)
            } else {
                setBlackScreen(context, false)
            }
        }

        private fun start(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(AutoRunnerApplication.TAG, "unable to start overlay service", error)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AutoRunnerOverlayService::class.java)) }
        }

        fun sendCommand(context: Context, action: String) = start(context, action)
    }
}
