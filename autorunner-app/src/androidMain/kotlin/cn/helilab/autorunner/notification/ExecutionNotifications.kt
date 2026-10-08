package cn.helilab.autorunner.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.helilab.autorunner.R
import cn.helilab.autorunner.overlay.AutoRunnerOverlayService
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionState

/**
 * Foreground notification used by `AutoRunnerOverlayService`.
 *
 * The channel id `autorunner_execution` is part of the public contract listed in
 * §1.1 of the design document. Showing progress in the notification is also the
 * mechanism that keeps long repeat runs alive when MIUI/ColorOS try to reclaim
 * the accessibility service (§6.3.4).
 */
object ExecutionNotifications {

    /** Fixed channel id (see §1.1). */
    const val CHANNEL_ID = "autorunner_execution"

    /** Fixed notification id so the notification is updated in place. */
    const val NOTIFICATION_ID = 0x4152

    /** Foreground notification shown while the gamepad calibration layer is up. */
    const val CALIBRATION_NOTIFICATION_ID = 0x4153

    const val ACTION_STOP = "cn.helilab.autorunner.action.STOP_EXECUTION"
    const val ACTION_TOGGLE_PAUSE = "cn.helilab.autorunner.action.TOGGLE_PAUSE_EXECUTION"

    /** 广播/服务动作：停止正在进行的录制（通知栏保底按钮）。 */
    const val ACTION_STOP_RECORDING = "cn.helilab.autorunner.action.STOP_RECORDING"

    /** Creates the channel once; safe to call repeatedly. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_execution),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_execution_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Builds the foreground notification for the current execution state. */
    fun build(context: Context, progress: ExecutionProgress): Notification {
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent().setClassName(context, "cn.helilab.autorunner.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when (progress.state) {
            ExecutionState.RUNNING, ExecutionState.PAUSED -> progress.scriptName.ifBlank {
                context.getString(R.string.notification_execution_title)
            }

            else -> context.getString(R.string.notification_execution_title)
        }

        val text = when (progress.state) {
            ExecutionState.IDLE -> context.getString(R.string.notification_execution_idle)
            ExecutionState.RUNNING -> "运行中 · 循环 ${progress.loopLabel} · 耗时 ${progress.elapsedLabel}"
            ExecutionState.PAUSED -> "已暂停 · 循环 ${progress.loopLabel}"
            ExecutionState.STOPPED -> "已停止 · 循环 ${progress.loopLabel}"
            ExecutionState.COMPLETED -> "已完成 · 循环 ${progress.loopLabel} · 耗时 ${progress.elapsedLabel}"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(progress.state.isActive)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress.totalActions > 0 && progress.currentActionIndex >= 0) {
            builder.setSubText(
                "动作 ${progress.currentActionIndex + 1}/${progress.totalActions}",
            )
        }

        progress.loopFraction?.let { fraction ->
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } ?: builder.setProgress(0, 0, progress.state == ExecutionState.RUNNING)

        if (progress.state.isActive) {
            val pauseLabel = if (progress.state == ExecutionState.PAUSED) {
                context.getString(R.string.notification_action_resume)
            } else {
                context.getString(R.string.notification_action_pause)
            }
            builder.addAction(
                0,
                pauseLabel,
                servicePendingIntent(context, ACTION_TOGGLE_PAUSE, 1),
            )
            builder.addAction(
                0,
                context.getString(R.string.notification_action_stop),
                servicePendingIntent(context, ACTION_STOP, 2),
            )
        }

        return builder.build()
    }

    /** Notification used when the foreground service starts. */
    fun buildIdle(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.notification_execution_title))
            .setContentText(context.getString(R.string.notification_execution_idle))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Notification shown while a recording is in progress. */
    fun buildRecording(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.notification_recording_title))
            .setContentText(context.getString(R.string.notification_recording_text))
            .addAction(
                0,
                context.getString(R.string.notification_action_stop_recording),
                servicePendingIntent(context, ACTION_STOP_RECORDING, 3),
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Foreground notification shown while the gamepad calibration layer is up. */
    fun buildCalibration(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("手柄按键标定")
            .setContentText("点击悬浮球展开按键，拖到游戏中的实际位置后保存")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun cancelCalibration(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(CALIBRATION_NOTIFICATION_ID)
    }

    private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, AutoRunnerOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
