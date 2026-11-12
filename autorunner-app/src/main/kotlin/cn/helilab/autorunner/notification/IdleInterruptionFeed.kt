package cn.helilab.autorunner.notification

/**
 * 挂机期间必须立刻把屏幕交还给用户的特殊情况。
 *
 * 目前包含来电（电话 / 第三方语音通话）与闹钟响铃：黑屏层会盖住整个屏幕，
 * 这两种情况下用户需要立即看到来电界面或关掉闹钟，因此不再继续挂机。
 *
 * [resolved = false] 表示特殊情况**开始**（触发自动退出黑屏），
 * [resolved = true] 表示特殊情况**结束**（电话挂断 / 闹钟停响，触发自动恢复）。
 */
data class IdleInterruption(
    val kind: Kind,
    val resolved: Boolean = false,
) {

    enum class Kind {
        /** 来电或通话中。 */
        CALL,

        /** 闹钟响铃。 */
        ALARM,
    }

    /** 提示文案里的中文名称，例如「来电」。 */
    val label: String
        get() = when (kind) {
            Kind.CALL -> "来电"
            Kind.ALARM -> "闹钟"
        }
}

/**
 * 通知监听器与黑屏挂机层之间的单槽桥：重要通知到达时通知黑屏层退出挂机，
 * 特殊情况结束时通知黑屏层恢复挂机。
 *
 * 与 [IdleNotificationFeed] 相同，只有黑屏层可见时才注册 [observer]；
 * 但 interruption 自动退出后 [observer] **会被保留**一小段时间，
 * 用来接收 resolved 信号再自动恢复黑屏。两端的调用都在主线程。
 */
object IdleInterruptionFeed {

    /** 接收 interruption 开始 / 结束事件；为 `null` 表示当前不关心。 */
    @Volatile
    var observer: ((IdleInterruption) -> Unit)? = null

    /** 当前是否有端在监听 interruption 事件。 */
    val isObserved: Boolean get() = observer != null

    /** 特殊情况**开始**（来电 / 闹钟出现）。 */
    fun publishStart(kind: IdleInterruption.Kind) {
        observer?.invoke(IdleInterruption(kind, resolved = false))
    }

    /** 特殊情况**结束**（电话挂断 / 闹钟停响）。 */
    fun publishResolved(kind: IdleInterruption.Kind) {
        observer?.invoke(IdleInterruption(kind, resolved = true))
    }
}
