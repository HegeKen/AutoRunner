package cn.helilab.autorunner.notification

import android.graphics.drawable.Drawable

/**
 * 挂机期间显示在黑屏层上的一条通知。
 *
 * 只保留渲染需要的字段，避免把完整的 [android.app.Notification]（可能包含
 * 大图、动作按钮等）长期持有在内存里。
 *
 * @param packageName 通知来源应用。
 * @param appLabel 来源应用名（用于卡片首行）。
 * @param title 通知标题，可能为空。
 * @param text 通知正文；过长时会被 [IdleNotificationView] 省略。
 * @param postedAtMs 发布时刻（`elapsedRealtime`），用于自动消失。
 * @param icon 小图标；部分通知没有，为 `null` 时只画文字。
 */
data class IdleNotification(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
    val icon: Drawable? = null,
)

/**
 * `NotificationListenerService` 与黑屏挂机层之间的单槽桥。
 *
 * 只有黑屏层可见时才注册 [observer]——这同时是「只在挂机期间收集通知」的开关：
 * 没有黑屏层时通知会被直接丢弃，不会进入任何内存队列。两端的调用都在主线程
 * （系统回调与窗口生命周期都在主线程），因此无需加锁。
 */
object IdleNotificationFeed {

    /** 黑屏层可见时接收通知；为 `null` 表示当前不收集。 */
    @Volatile
    var observer: ((IdleNotification) -> Unit)? = null

    /** 当前是否有黑屏层在接收通知（也用于调试状态输出）。 */
    val isObserved: Boolean get() = observer != null

    fun publish(notification: IdleNotification) {
        observer?.invoke(notification)
    }
}
