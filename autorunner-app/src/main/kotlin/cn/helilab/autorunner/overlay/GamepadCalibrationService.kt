package cn.helilab.autorunner.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.helilab.autorunner.AppGraph
import cn.helilab.autorunner.AutoRunnerApplication
import cn.helilab.autorunner.notification.ExecutionNotifications
import com.autorunner.ui.overlay.GamepadCalibrationPanel
import com.autorunner.ui.theme.AutoRunnerTheme
import com.autorunner.ui.viewmodel.GamepadCalibrationViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 持有手柄校准悬浮层的前台服务。
 *
 * 该服务与 `AutoRunnerOverlayService` 结构相似（前台通知 + `WindowManager`
 * 悬浮层），但专用于校准：它渲染 [GamepadCalibrationPanel]，这是一个常驻的
 * 全屏层，用户可将每个按键拖动到真实游戏控件之上。底部的悬浮球通过长按菜单
 * 提供重置、转屏、穿透、取消、保存并退出；标定期间长按音量减键也可随时退出
 * （由无障碍服务处理）。
 */
class GamepadCalibrationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var host: GamepadCalibrationWindowHost? = null

    private lateinit var viewModel: GamepadCalibrationViewModel

    /** 是否已通过 [OrientationLockWindow] 把整屏固定为横屏（「转屏」开关状态）。 */
    private var orientationForced by mutableStateOf(false)

    private var orientationWindow: OrientationLockWindow? = null

    /** 「暂时穿透」激活期间的恢复胶囊；`null` 表示当前未穿透。 */
    private var passthroughWindow: CalibrationPassthroughWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GamepadCalibrationStateHolder.setActive(true)
        viewModel = GamepadCalibrationViewModel(AppGraph.requireContainer())
        startForegroundSafely()
        attachOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        passthroughWindow?.remove()
        passthroughWindow = null
        host?.remove()
        host = null
        orientationWindow?.release()
        orientationWindow = null
        GamepadCalibrationStateHolder.setActive(false)
        ExecutionNotifications.cancelCalibration(this)
        scope.cancel()
        super.onDestroy()
    }

    // --------------------------------------------------------------- 悬浮层

    private fun attachOverlay() {
        val windowHost = GamepadCalibrationWindowHost(this)
        host = windowHost
        val themeMode = AppGraph.requireContainer().settingsRepository.current.themeMode

        val attached = windowHost.show {
            AutoRunnerTheme(themeMode = themeMode) {
                GamepadCalibrationPanel(
                    viewModel = viewModel,
                    onSave = {
                        // 「保存并退出」：坐标已由面板写入全局映射，回到 App。
                        bringAppToFront()
                        stopSelf()
                    },
                    onCancel = { stopSelf() },
                    orientationForced = orientationForced,
                    onToggleOrientation = ::toggleOrientation,
                    onEnablePassthrough = ::enablePassthrough,
                )
            }
        }

        if (!attached) {
            Log.w(AutoRunnerApplication.TAG, "gamepad calibration overlay could not be attached")
            GamepadCalibrationStateHolder.setActive(false)
            stopSelf()
        }
    }

    /**
     * 「转屏」开关：用透明悬浮窗把整屏固定为横屏；再点一次恢复系统方向。
     *
     * 画布随屏幕旋转缩放后，[GamepadCalibrationViewModel.ensureLayout] 会检测到
     * 横竖翻转并按新方向重铺按键布局，无需在此手动重置。
     */
    private fun toggleOrientation() {
        val window = orientationWindow ?: OrientationLockWindow(this).also { orientationWindow = it }
        when {
            orientationForced -> {
                window.release()
                orientationForced = false
                Toast.makeText(this, "已恢复系统方向", Toast.LENGTH_SHORT).show()
            }

            window.forceLandscape() -> {
                orientationForced = true
                Toast.makeText(this, "已切换为横屏，按键布局将按横屏重排", Toast.LENGTH_SHORT).show()
            }

            else -> Toast.makeText(this, "转屏失败：缺少「显示在其他应用上层」权限", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 「暂时穿透」：把标定层整层设为 `FLAG_NOT_TOUCHABLE`，让用户先启动真实游戏、
     * 进入目标场景（按键芯片保持可见作为参照），再点常驻的「恢复标定操作」胶囊
     * 回到拖拽标定。穿透期间长按音量减键退出仍然可用。
     */
    private fun enablePassthrough() {
        if (passthroughWindow?.isShowing == true) return
        val windowHost = host ?: return
        if (!windowHost.setTouchable(false)) {
            Toast.makeText(this, "穿透失败：无法更新标定层窗口", Toast.LENGTH_SHORT).show()
            return
        }
        val pill = CalibrationPassthroughWindow(this, ::restorePassthrough)
        if (!pill.show()) {
            // 胶囊加不上必须立刻把触摸还给标定层，否则用户失去唯一操作入口。
            windowHost.setTouchable(true)
            Toast.makeText(this, "穿透失败：缺少「显示在其他应用上层」权限", Toast.LENGTH_SHORT).show()
            return
        }
        passthroughWindow = pill
        Toast.makeText(this, "标定层已暂时穿透；点顶部「恢复标定操作」胶囊恢复", Toast.LENGTH_LONG).show()
    }

    /** 恢复胶囊点按回调：撤掉胶囊并把触摸还给标定层。 */
    private fun restorePassthrough() {
        passthroughWindow?.remove()
        passthroughWindow = null
        host?.setTouchable(true)
        Toast.makeText(this, "已恢复标定操作", Toast.LENGTH_SHORT).show()
    }

    /** 校准完成后把 App 重新带回前台。 */
    private fun bringAppToFront() {
        runCatching {
            startActivity(
                Intent()
                    .setClassName(this, "cn.helilab.autorunner.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private fun startForegroundSafely() {
        val notification = ExecutionNotifications.buildCalibration(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ExecutionNotifications.CALIBRATION_NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(ExecutionNotifications.CALIBRATION_NOTIFICATION_ID, notification)
            }
        }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "startForeground failed", error)
        }
    }

    companion object {

        /** 启动（或重新下达指令给）校准服务。 */
        fun start(context: Context) {
            val intent = Intent(context, GamepadCalibrationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(AutoRunnerApplication.TAG, "unable to start gamepad calibration service", error)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GamepadCalibrationService::class.java)) }
        }
    }
}
