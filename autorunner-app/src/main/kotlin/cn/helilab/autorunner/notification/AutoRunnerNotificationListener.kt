package cn.helilab.autorunner.notification

import android.app.Notification
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import cn.helilab.autorunner.AppGraph
import cn.helilab.autorunner.AutoRunnerApplication

/**
 * 系统「通知使用权」的接收端：把挂机期间新到的通知交给黑屏层。
 *
 * 只做三件事：
 * 1. 过滤掉不该出现在挂机界面上的通知（本应用、常驻 / 分组摘要、系统 UI 噪音、
 *    媒体通知）；
 * 2. 从 `Notification.extras` 提取标题 / 正文 / 小图标与应用名；
 * 3. 通过 [IdleNotificationFeed] 投递给当前可见的黑屏层。
 *
 * 通知**只在黑屏层可见时收集**：没有黑屏层（`IdleNotificationFeed.observer`
 * 为 `null`）或用户关闭「显示手机通知」时，回调会立即返回，不保留任何内容。
 * 监听器本身不持有 Context 之外的长生命周期状态，系统可以在任意时刻重新绑定。
 *
 * 同一份「通知使用权」也是 [IdleMediaMonitor] 读取系统媒体会话（黑屏挂机上的
 * 歌曲信息与上一首 / 下一首按钮）的前提。媒体通知因此不再作为普通卡片转发：
 * 面板已经展示并控制该会话，重复转发只会干扰。
 *
 * 焦点通知（超级岛）走另一条支路：它们代表仍在进行的服务状态，需要**持续**
 * 展示而不是 8 秒后消失，因此交给 [FocusNotificationFeed] 维护「当前有效集合」，
 * 由黑屏面板渲染成常驻的岛状态行。
 */
class AutoRunnerNotificationListener : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 本实例注册到 [FocusNotificationFeed] 的回查函数；解绑时按身份撤销。 */
    private var focusRefresh: (() -> Unit)? = null

    /** 已触发过 interruption 的通知 key → 触发的 kind；removed 时补发 resolved。 */
    private val triggeredInterruptionKeys = HashMap<String, IdleInterruption.Kind>()

    /** 应用名 / 图标缓存：同一条通知流里反复查询 `PackageManager` 没有意义。 */
    private val appLabels = HashMap<String, String>()

    private val appIcons = HashMap<String, Drawable?>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(AutoRunnerApplication.TAG, "notification listener connected")
        val refresh: () -> Unit = { publishActiveFocus() }
        focusRefresh = refresh
        FocusNotificationFeed.refresh = refresh
        // 黑屏层可能先于监听器绑定：这里主动补一次快照。
        if (FocusNotificationFeed.isObserved) refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(AutoRunnerApplication.TAG, "notification listener disconnected, requesting rebind")
        detachFocusRefresh()
        // 被系统回收或应用更新后主动请求重新绑定；用户撤销授权时该请求会被忽略。
        mainHandler.postDelayed(
            {
                runCatching {
                    requestRebind(ComponentName(this, AutoRunnerNotificationListener::class.java))
                }
            },
            REBIND_DELAY_MS,
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        triggeredInterruptionKeys.clear()
        detachFocusRefresh()
        super.onDestroy()
    }

    /** 监听器解绑后，[FocusNotificationFeed.refresh] 不能再指向已销毁的实例。 */
    private fun detachFocusRefresh() {
        if (FocusNotificationFeed.refresh === focusRefresh) FocusNotificationFeed.refresh = null
        focusRefresh = null
    }

    /** 回查系统里当前的活动通知，补上「订阅前就存在」的岛状态。 */
    private fun publishActiveFocus() {
        val current = runCatching { activeNotifications }.getOrNull() ?: return
        val observedKeys = HashSet<String>(current.size)
        current.forEach { sbn ->
            val focus = sbn?.let(::publishFocus) ?: return@forEach
            observedKeys += focus.key
        }
        // 系统里已经消失、但我们没收到 removed 回调的通知，一并清掉。
        FocusNotificationFeed.retainOnly(observedKeys)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        FocusNotificationFeed.remove(sbn?.key ?: return)
        // 这条通知曾触发 interruption（来电 / 闹钟），现在它被系统撤掉了 →
        // 闹钟停响了（CALL 的 resolved 主要靠音频模式，这里只是 fallback）。
        val key = sbn.key
        val kind = triggeredInterruptionKeys.remove(key)
        if (kind != null && IdleInterruptionFeed.isObserved) {
            Log.i(
                AutoRunnerApplication.TAG,
                "idle interruption resolved via notification removed: kind=$kind key=$key",
            )
            IdleInterruptionFeed.publishResolved(kind)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBarNotification = sbn ?: return
        val settings = AppGraph.containerOrNull?.settingsRepository?.current ?: return
        // 焦点通知是常驻状态，与普通通知卡片的收集开关彼此独立。
        if (settings.idleFocusDisplay) publishFocus(statusBarNotification)
        // 来电 / 闹钟：黑屏层整屏遮挡，需要立刻让出屏幕（不受「显示手机通知」开关影响）。
        publishInterruption(statusBarNotification)
        // 没有可见的黑屏层 → 不在挂机模式，直接丢弃（也避免持有通知内容）。
        if (!IdleNotificationFeed.isObserved) return
        if (!settings.idleNotificationDisplay) {
            Log.i(
                AutoRunnerApplication.TAG,
                "idle notification dropped: display disabled (${statusBarNotification.packageName})",
            )
            return
        }
        if (!isWanted(statusBarNotification)) return
        val extracted = extract(statusBarNotification)
        if (extracted == null) {
            Log.i(
                AutoRunnerApplication.TAG,
                "idle notification dropped: no title/text (${statusBarNotification.packageName})",
            )
            return
        }
        IdleNotificationFeed.publish(extracted)
    }

    /**
     * 把一条通知写进焦点通知集合；不是岛通知时返回 `null`。
     *
     * `miui.focus.param` 里的 `cancel = true` 表示服务结束，此时按「移除」处理，
     * 即使系统还没把通知撤下来也不再展示。
     */
    private fun publishFocus(sbn: StatusBarNotification): FocusNotification? {
        if (!FocusNotificationFeed.isObserved) return null
        if (sbn.packageName == packageName) return null
        val focus = sbn.toFocusNotification(appLabel(sbn.packageName), SystemClock.elapsedRealtime())
        if (focus == null) {
            FocusNotificationFeed.remove(sbn.key)
            return null
        }
        FocusNotificationFeed.upsert(focus)
        return focus
    }

    /**
     * 来电 / 闹钟通知：通知黑屏层自动退出挂机；通知撤掉时补发 resolved。
     *
     * 黑屏层收起后 [IdleInterruptionFeed.isObserved] 仍会短暂保持 `true`
     * （用于接收 resolved 信号自动恢复）。与「显示手机通知」开关无关——
     * 这是把屏幕交还给用户的安全行为。
     */
    private fun publishInterruption(sbn: StatusBarNotification) {
        if (!IdleInterruptionFeed.isObserved) return
        if (sbn.packageName == packageName) return
        val kind = when (sbn.notification?.category) {
            Notification.CATEGORY_CALL -> IdleInterruption.Kind.CALL
            Notification.CATEGORY_ALARM -> IdleInterruption.Kind.ALARM
            else -> return
        }
        // 记录这条通知 key，removed 时补发 resolved。
        triggeredInterruptionKeys[sbn.key] = kind
        Log.i(
            AutoRunnerApplication.TAG,
            "idle interruption notification: kind=$kind pkg=${sbn.packageName}",
        )
        IdleInterruptionFeed.publishStart(kind)
    }

    private fun isWanted(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        // 系统 UI 的通知（USB 调试、截图、电池提示等）与挂机内容无关。
        if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") return false
        // 常驻通知（前台服务、音乐播放器、下载进度）会持续刷新，展示价值低且会刷屏。
        if (sbn.isOngoing) return false
        val notification = sbn.notification ?: return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // 焦点通知（超级岛）已经由面板的岛状态行接管，不再重复弹 8 秒卡片。
        if (notification.extras?.containsKey(FocusNotificationExtras.PARAM) == true) {
            Log.i(
                AutoRunnerApplication.TAG,
                "idle notification dropped: focus notification (${sbn.packageName})",
            )
            return false
        }
        // 媒体通知已由黑屏面板的媒体控制接管，不再重复显示成通知卡片。
        if (isMediaNotification(notification)) {
            Log.i(
                AutoRunnerApplication.TAG,
                "idle notification dropped: media notification (${sbn.packageName})",
            )
            return false
        }
        return true
    }

    /**
     * 判断一条通知是否属于媒体播放控制。
     *
     * 现代播放器至少会命中以下一种标记：`CATEGORY_TRANSPORT`、
     * `EXTRA_MEDIA_SESSION` 或 `MediaStyle` 模板。命中后不再把通知转发到
     * [IdleNotificationFeed]——歌曲信息与切歌按钮由 [IdleMediaMonitor] 统一提供。
     */
    private fun isMediaNotification(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = notification.extras ?: return false
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        val template = extras.getCharSequence(Notification.EXTRA_TEMPLATE)?.toString().orEmpty()
        return template.contains("MediaStyle", ignoreCase = true)
    }

    private fun extract(sbn: StatusBarNotification): IdleNotification? {
        val notification = sbn.notification ?: return null
        val extras: Bundle = notification.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        val text = sequenceOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        ).mapNotNull { key ->
            extras.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.firstOrNull()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                ?.joinToString(" ")
                ?.takeIf { it.isNotEmpty() }
            ?: ""
        if (title.isEmpty() && text.isEmpty()) return null
        return IdleNotification(
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = title.take(MAX_TITLE_CHARS),
            text = text.take(MAX_TEXT_CHARS),
            postedAtMs = SystemClock.elapsedRealtime(),
            // 用应用图标而不是 notification.smallIcon：后者是别的应用的资源 id，
            // 用本应用的 Resources 加载可能取到不相干的图（或直接失败）。
            icon = appIcon(sbn.packageName),
        )
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) {
        runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    private fun appIcon(pkg: String): Drawable? = appIcons.getOrPut(pkg) {
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    private companion object {
        /** 断开后等待系统完成清理，再请求重新绑定。 */
        const val REBIND_DELAY_MS = 1_500L

        /** 单条通知的标题 / 正文展示上限，避免超大文案拖慢黑屏层绘制。 */
        const val MAX_TITLE_CHARS = 80
        const val MAX_TEXT_CHARS = 160
    }
}
