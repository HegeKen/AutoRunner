package cn.helilab.autorunner.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.helilab.autorunner.R
import cn.helilab.autorunner.overlay.AutoRunnerOverlayService
import com.autorunner.core.execution.ComboProgress
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.util.currentTimeMillis
import kotlin.math.roundToInt

/**
 * `AutoRunnerOverlayService` 使用的前台通知。
 *
 * 渠道 id `autorunner_execution` 是设计文档 §1.1 列出的公开契约的一部分。
 * 在通知中展示进度，同时也是在 MIUI/ColorOS 试图回收无障碍服务时
 * 保持长时间循环运行存活的机制（§6.3.4）。
 */
object ExecutionNotifications {

    /** 固定的渠道 id（见 §1.1）。 */
    const val CHANNEL_ID = "autorunner_execution"

    /**
     * 超级岛焦点通知的独立渠道。
     *
     * 官方示例使用 `IMPORTANCE_DEFAULT`；前台服务通知仍用 LOW，避免岛通知
     * 被低重要性渠道限制。渠道静音，不会额外响铃。
     */
    const val FOCUS_CHANNEL_ID = "autorunner_focus"

    /** 固定的通知 id，使通知能够原地更新。 */
    const val NOTIFICATION_ID = 0x4152

    /** 手柄标定层显示时展示的前台通知。 */
    const val CALIBRATION_NOTIFICATION_ID = 0x4153

    const val ACTION_STOP = "cn.helilab.autorunner.action.STOP_EXECUTION"
    const val ACTION_TOGGLE_PAUSE = "cn.helilab.autorunner.action.TOGGLE_PAUSE_EXECUTION"

    /** 广播/服务动作：停止正在进行的录制（通知栏保底按钮）。 */
    const val ACTION_STOP_RECORDING = "cn.helilab.autorunner.action.STOP_RECORDING"

    /** 创建渠道一次；可重复调用。 */
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

    /** 创建超级岛焦点通知渠道；可重复调用。 */
    fun ensureFocusChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(FOCUS_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            FOCUS_CHANNEL_ID,
            context.getString(R.string.notification_channel_focus),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_focus_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 根据当前执行状态构建前台通知。 */
    fun build(context: Context, progress: ExecutionProgress): Notification {
        ensureChannel(context)

        val contentIntent = contentIntent(context)

        val title = when (progress.state) {
            ExecutionState.RUNNING, ExecutionState.PAUSED -> progress.scriptName.ifBlank {
                context.getString(R.string.notification_execution_title)
            }

            else -> context.getString(R.string.notification_execution_title)
        }

        // 循环间隔 / 首轮等待期间追加「间隔 7/14 分钟」实时进度
        // （对应 label 为 null 时不显示）。
        val intervalSuffix = progress.intervalLabel?.let { " · 间隔 $it" }.orEmpty()
        val startDelaySuffix = progress.startDelayLabel?.let { " · 首轮等待 $it" }.orEmpty()
        val waitSuffix = intervalSuffix + startDelaySuffix
        val text = when (progress.state) {
            ExecutionState.IDLE -> context.getString(R.string.notification_execution_idle)
            ExecutionState.RUNNING -> "运行中 · 循环 ${progress.loopLabel}$waitSuffix · 耗时 ${progress.elapsedLabel}"
            ExecutionState.PAUSED -> "已暂停 · 循环 ${progress.loopLabel}$waitSuffix"
            ExecutionState.STOPPED -> "已停止 · 循环 ${progress.loopLabel}"
            ExecutionState.COMPLETED -> "已完成 · 循环 ${progress.loopLabel} · 耗时 ${progress.elapsedLabel}"
        }

        // 不要给前台通知设 group summary。MIUI 在分组里只有 summary、没有 child
        // 时会隐藏整组通知（实测组合运行的通知栏条目直接消失）；超级岛焦点通知
        // 若可用也按独立通知处理。
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

    /**
     * 执行期间的小米超级岛 / 焦点通知。
     *
     * [combo] 活动时上报组合运行（双进度 + A/B 双圆环）；否则按单脚本运行上报
     * （脚本名 + 单进度条 + 单圆环）。
     *
     * 返回 `null` 表示当前设备不支持 OS3 超级岛（或不是小米设备），调用方应
     * 取消可能残留的焦点通知；普通前台通知不受影响。
     */
    fun buildFocusIsland(
        context: Context,
        progress: ExecutionProgress,
        combo: ComboProgress?,
    ): Notification? {
        if (!ExecutionFocusIsland.supportsIsland(context)) return null
        ensureFocusChannel(context)
        val activeCombo = combo?.takeIf { it.isActive }
        val title: String
        val text: String
        if (activeCombo != null) {
            title = "组合运行"
            text = "循环 ${progress.completedLoops} 次 · 已运行 " +
                ExecutionProgress.formatDuration(activeCombo.liveElapsedMs())
        } else {
            title = progress.scriptName.ifBlank { "AutoRunner" }
            text = "循环 ${progress.loopLabel} · 已运行 ${progress.elapsedLabel}"
        }
        val notification = NotificationCompat.Builder(context, FOCUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        notification.extras.putString(
            FocusNotificationExtras.PARAM,
            if (activeCombo != null) {
                ExecutionFocusIsland.buildPayload(progress, activeCombo)
            } else {
                ExecutionFocusIsland.buildSinglePayload(progress)
            },
        )
        // 原生岛没有单/双进度字段：用运行时生成的圆环位图表达当前百分比。
        val pics = Bundle()
        if (activeCombo != null) {
            val now = currentTimeMillis()
            val percentA = ((activeCombo.slot(ComboSlotRole.A)?.cycleFractionAt(now) ?: 0f) * 100f)
                .roundToInt().coerceIn(0, 100)
            val percentB = ((activeCombo.slot(ComboSlotRole.B)?.cycleFractionAt(now) ?: 0f) * 100f)
                .roundToInt().coerceIn(0, 100)
            pics.putParcelable(
                ExecutionFocusIsland.RING_A_PIC_KEY,
                Icon.createWithBitmap(
                    ExecutionFocusIsland.buildRingBitmap(percentA, "A", "#2655FF"),
                ),
            )
            pics.putParcelable(
                ExecutionFocusIsland.RING_B_PIC_KEY,
                Icon.createWithBitmap(
                    ExecutionFocusIsland.buildRingBitmap(percentB, "B", "#00B8A9"),
                ),
            )
        } else {
            val percent = ExecutionFocusIsland.singlePercent(progress)
            pics.putParcelable(
                ExecutionFocusIsland.RING_SINGLE_PIC_KEY,
                Icon.createWithBitmap(ExecutionFocusIsland.buildSingleRingBitmap(percent)),
            )
        }
        notification.extras.putBundle("miui.focus.pics", pics)
        return notification
    }

    /** 结束 / 取消执行期间的小米超级岛焦点通知。 */
    fun cancelFocusIsland(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(ExecutionFocusIsland.NOTIFICATION_ID)
        }
    }

    /** 前台服务启动时使用的通知。 */
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

    /** 录制进行中展示的通知。 */
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

    /** 手柄标定层显示时展示的前台通知。 */
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

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent().setClassName(context, "cn.helilab.autorunner.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, AutoRunnerOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
