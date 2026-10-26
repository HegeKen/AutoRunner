package cn.helilab.autorunner.notification

/**
 * `NotificationListenerService` 与黑屏挂机层之间的焦点通知桥。
 *
 * 与 [IdleNotificationFeed]（单条、8 秒后消失）不同，这里维护的是**当前仍然
 * 有效的焦点通知集合**：同一条通知原地更新（外卖从「已接单」到「已送达」），
 * 通知被移除时从集合里删除。
 *
 * 只在黑屏层可见且用户开启「显示焦点通知」时才收集——[observer] 为 `null`
 * 时不会保留任何通知内容。订阅瞬间会通过 [refresh] 回查一次系统里的活动通知，
 * 避免「先有外卖、后开黑屏」时看不到已有状态。
 */
object FocusNotificationFeed {

    private val active = LinkedHashMap<String, FocusNotification>()

    /** 黑屏层可见时接收全量快照；置空时清空缓存。 */
    @Volatile
    var observer: ((List<FocusNotification>) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) {
                active.clear()
            } else {
                // 先回查系统的活动通知，再推一次快照（回查可能同步触发 publish）。
                refresh?.invoke()
                publish()
            }
        }

    /** 由监听器注册：重新扫描一遍当前活动通知。监听器未绑定时为 `null`。 */
    @Volatile
    var refresh: (() -> Unit)? = null

    val isObserved: Boolean get() = observer != null

    fun upsert(notification: FocusNotification) {
        if (!isObserved) return
        active[notification.key] = notification
        publish()
    }

    fun remove(key: String) {
        if (active.remove(key) != null) publish()
    }

    /** 只保留 [keys] 里的通知，用于回查后清理漏掉的 removed 回调。 */
    fun retainOnly(keys: Set<String>) {
        if (active.keys.retainAll(keys)) publish()
    }

    fun clear() {
        if (active.isNotEmpty()) {
            active.clear()
            publish()
        }
    }

    /** 供调试命令输出当前状态。 */
    fun snapshot(): List<FocusNotification> = active.values.sortedByDescending { it.postedAtMs }

    private fun publish() {
        observer?.invoke(snapshot())
    }
}
