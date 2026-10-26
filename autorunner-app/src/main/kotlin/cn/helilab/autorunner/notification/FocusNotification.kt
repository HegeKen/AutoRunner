package cn.helilab.autorunner.notification

import android.graphics.Color
import android.service.notification.StatusBarNotification
import android.app.Notification
import com.autorunner.core.notification.FocusPayloadParser

/**
 * 黑屏挂机上展示的一条焦点通知 / 超级岛状态。
 *
 * 与 [IdleNotification]（8 秒后自动消失的普通通知卡片）不同，焦点通知代表的是
 * **仍在进行中的服务**（外卖、打车、计时、投屏……），因此只要通知还在系统里就
 * 持续展示，通知被移除或 `cancel` 后立即消失。
 *
 * @param key 系统通知 key（`StatusBarNotification.key`），用于原地更新与移除。
 * @param packageName 来源应用包名。
 * @param appLabel 应用名，显示在文案前面（「美团外卖 · 预计 21:07 送达」）。
 * @param title 主文案。
 * @param detail 补充说明，可能为空。
 * @param progress 0..100 的进度；没有进度时为 `null`。
 * @param accentColor 强调色（已解析为 ARGB），用于进度条。
 * @param postedAtMs 最近一次更新的 `elapsedRealtime`，用于排序。
 */
data class FocusNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val detail: String,
    val progress: Int?,
    val accentColor: Int?,
    val postedAtMs: Long,
)

/** 焦点通知在通知 extras 里使用的固定 key。 */
object FocusNotificationExtras {
    /** 客户端 / MiPush 下发的岛参数（JSON 字符串）。 */
    const val PARAM = "miui.focus.param"

    /** 媒体通知自动上岛时的参数，只有拖拽分享信息，正文由媒体会话提供。 */
    const val MEDIA = "miui.focus.param.media"

    /** `Bundle`，key 为 `miui.focus.pic_*`，值为 `Icon`。 */
    const val PICS = "miui.focus.pics"

    /** `Bundle`，key 为 `miui.focus.action_*`，值为 `Notification.Action`。 */
    const val ACTIONS = "miui.focus.actions"

    /** 系统登记焦点通知的 Secure 设置项，内容是通知 key 的 JSON 数组。 */
    const val SETTINGS_KEY = "focus_notifs"
}

/**
 * 把一条系统通知转换成 [FocusNotification]。
 *
 * 只认 `miui.focus.param`：媒体通知（`miui.focus.param.media`）已经由
 * [IdleMediaMonitor] 在面板里展示歌曲与切歌按钮，这里返回 `null` 避免重复。
 * `param_v2.cancel = true` 表示这条通知要求直接结束，同样返回 `null`。
 */
fun StatusBarNotification.toFocusNotification(appLabel: String, nowMs: Long): FocusNotification? {
    val extras = notification?.extras ?: return null
    if (extras.containsKey(FocusNotificationExtras.MEDIA)) return null
    val raw = extras.getString(FocusNotificationExtras.PARAM) ?: return null
    if (FocusPayloadParser.isCancel(raw)) return null
    val payload = FocusPayloadParser.parse(raw) ?: return null
    return FocusNotification(
        key = key,
        packageName = packageName,
        appLabel = appLabel,
        title = payload.title.ifEmpty { fallbackTitle(notification) },
        detail = payload.detail,
        progress = payload.progress,
        accentColor = payload.accentColor?.toArgbOrNull(),
        postedAtMs = nowMs,
    )
}

/** 负载里没有主文案时退回系统通知标题，避免出现只有应用名的空行。 */
private fun fallbackTitle(notification: Notification): String =
    notification.extras?.getCharSequence(Notification.EXTRA_TITLE)
        ?.toString()?.trim().orEmpty()

private fun String.toArgbOrNull(): Int? = runCatching { Color.parseColor(this) }.getOrNull()
