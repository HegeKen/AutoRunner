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
 * Foreground service that owns the floating control panel (§6.4).
 *
 * * runs in the foreground with the fixed `autorunner_execution` channel, which
 *   both satisfies the "startForeground within 5 s" rule and keeps the process
 *   (and therefore the accessibility service) alive during long repeat runs,
 * * renders [FloatingControlPanel] inside a `WindowManager` overlay,
 * * mirrors the execution progress into the notification with pause / stop
 *   actions (§6.3.3).
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape.value = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 屏幕宽高已变化，让悬浮窗按新尺寸重算位置，避免球留在屏幕外。
        host?.onConfigurationChanged()
    }

    override fun onCreate() {
        super.onCreate()
        // 属性初始化阶段 base context 尚未附加，resources 会为 null，故在此读取方向。
        isLandscape.value =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        _isRunning.value = true
        OverlayStateHolder.update { it.copy(visible = true, error = null, permissionGranted = true) }

        val container = AppGraph.requireContainer()
        executionViewModel = ExecutionViewModel(container)
        recordingViewModel = RecordingViewModel(container)

        toastNotifier = ExecutionToastNotifier(this) {
            container.settingsRepository.current.executionReminder
        }.also { it.bind(scope, executionViewModel) }

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
                // The HID profile is fixed at registration time; nothing to do
                // here beyond updating the persisted preference (handled by the
                // settings screen).
            }
        }
        OverlayStateHolder.update { it.copy(menuOpen = host?.menuOpen?.value ?: false, visible = true) }
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        progressJob?.cancel()
        recordingJob?.cancel()
        pendingJob?.cancel()
        host?.remove()
        host = null
        OverlayStateHolder.update { it.copy(visible = false) }
        ExecutionNotifications.cancel(this)
        scope.cancel()
        super.onDestroy()
    }

    // --------------------------------------------------------------- overlay

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

    /** Keeps the foreground notification in sync with the executor. */
    private fun observeProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            executionViewModel.progress.collect { refreshNotification() }
        }
    }

    /**
     * Keeps the foreground notification in sync with recording. Without this the
     * "stop recording" action would never appear while a recording is running.
     */
    private fun observeRecording() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            recordingViewModel.status.collect { refreshNotification() }
        }
    }

    /**
     * Arms this service's own ViewModels from one-shot requests posted by the
     * in-app ViewModels ("run script X" / "start recording"), then collapses to
     * the ball so a single tap starts the work.
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
     * Rebuilds the foreground notification from the current execution and
     * recording state, so the actions always match what the user can do.
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
        }
    }

    companion object {

        private val _isRunning = MutableStateFlow(false)

        /** `true` while the overlay window is attached. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        const val ACTION_EXPAND = "cn.helilab.autorunner.action.EXPAND_OVERLAY"
        const val ACTION_COLLAPSE = "cn.helilab.autorunner.action.COLLAPSE_OVERLAY"
        const val ACTION_TOGGLE = "cn.helilab.autorunner.action.TOGGLE_OVERLAY"
        const val ACTION_SET_MODE = "cn.helilab.autorunner.action.SET_OVERLAY_MODE"

        /** Starts (or re-commands) the overlay service. */
        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, AutoRunnerOverlayService::class.java).apply {
                if (action != null) this.action = action
            }
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
