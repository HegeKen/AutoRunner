package cn.helilab.autorunner.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
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
 * Foreground service that owns the gamepad calibration overlay.
 *
 * The service mirrors `AutoRunnerOverlayService` (foreground notification +
 * `WindowManager` overlay) but is dedicated to calibration: it renders
 * [GamepadCalibrationPanel], a permanent full screen layer where the user drags
 * every button onto the real game controls. A floating ball at the bottom opens
 * a long-press menu to reset, cancel or save &amp; exit.
 */
class GamepadCalibrationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var host: GamepadCalibrationWindowHost? = null

    private lateinit var viewModel: GamepadCalibrationViewModel

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
        host?.remove()
        host = null
        GamepadCalibrationStateHolder.setActive(false)
        ExecutionNotifications.cancelCalibration(this)
        scope.cancel()
        super.onDestroy()
    }

    // --------------------------------------------------------------- overlay

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
                )
            }
        }

        if (!attached) {
            Log.w(AutoRunnerApplication.TAG, "gamepad calibration overlay could not be attached")
            GamepadCalibrationStateHolder.setActive(false)
            stopSelf()
        }
    }

    /** Brings the app back to the foreground after calibration finishes. */
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

        /** Starts (or re-commands) the calibration service. */
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
