package cn.helilab.autorunner.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import cn.helilab.autorunner.AppGraph
import cn.helilab.autorunner.AutoRunnerApplication
import com.autorunner.core.model.AppSettings
import com.autorunner.core.model.GamepadMappings
import com.autorunner.core.model.GamepadStickKeys
import com.autorunner.core.recording.RawTouchEvent
import com.autorunner.core.recording.TouchPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 手势录制与回放的核心（§6.1）。
 *
 * * `onServiceConnected` 将实例发布到 [AccessibilityServiceHolder]，
 *   以便 `AndroidAccessibilityController` 能派发手势、
 *   `AndroidRecordingController` 能挂载采集层。
 * * 采集层是一个 `TYPE_ACCESSIBILITY_OVERLAY` 视图，因此录制永远
 *   不需要 `SYSTEM_ALERT_WINDOW` 权限。
 * * `onUnbind` 会立即卸载采集层：遗留一个全屏触摸消费者
 *   会让设备无法使用。
 */
class AutoRunnerAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var captureView: TouchCaptureView? = null

    private var touchListener: ((RawTouchEvent) -> Unit)? = null

    /** 观察设置变化的协程作用域（motionEventSources 开关 / 右摇杆收尾）。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var settingsJob: Job? = null

    // 触摸采集刻意*不*使用 `AccessibilityServiceInfo.motionEventSources`。
    // 平台文档写明“来自 getMotionEventSources() 中来源的 MotionEvent
    // 不会被发送到系统的其余部分”，也就是说请求 SOURCE_TOUCHSCREEN 会让
    // 本服务吞掉设备上的每一次触摸。在 onServiceConnected() 中这么做，
    // 会在用户授予权限的那一刻让整块屏幕变成砖。
    // 因此触摸采集完全由设计文档 §6.1.1 描述的透明
    // TYPE_ACCESSIBILITY_OVERLAY 层完成，从而保持正常的窗口寻址
    // （以及浮动停止控件）不受影响。
    //
    // 例外：「鼠标 / 触控板控制右摇杆」开启时（API 34+）按需请求
    // SOURCE_MOUSE | SOURCE_TOUCHPAD——捕获鼠标/触控板移动正是该功能的语义
    // （把指针设备当成右摇杆），且不会影响触摸屏；关闭后立即归零。

    override fun onServiceConnected() {
        super.onServiceConnected()
        val previous = serviceInfo
        serviceInfo = (previous ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                // 平板键盘映射：请求系统把物理键盘事件先交给本服务的
                // onKeyEvent。只有用户在设置里显式开启键盘映射、且按键命中
                // 已绑定键位时才会吞键，其余事件一律放行。
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
            // 防御性重置：清除之前版本可能注册过的动作事件来源，
            // 否则触摸屏会一直处于被吞掉的状态，直到用户手动禁用服务。
            // setMotionEventSources 是 API 34+；在较旧的设备上该方法不存在，
            // 调用会让 onServiceConnected 崩溃（NoSuchMethodError）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                motionEventSources = 0
            }
        }
        // 防御性清理：如果进程在某个录制会话持有全屏采集层时被重启，
        // 那一层会继续吞掉设备上的每一次触摸。先把它丢掉，再做其他事。
        detachTouchCapture()
        AccessibilityServiceHolder.attach(this)
        startSettingsObserver()
        // getMotionEventSources 是 API 34+；加保护，确保这段诊断日志
        // 在旧设备上永远不会让服务崩溃。
        val motionSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { serviceInfo?.motionEventSources }.getOrNull()
        } else {
            null
        }
        Log.i(
            AutoRunnerApplication.TAG,
            "accessibility service connected: motionEventSources=" +
                "$motionSources (0 = touches reach apps)",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 手势回放不需要事件流；保留这个钩子是为了让服务保持绑定状态，
        // 不会被系统优化掉。
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        settingsJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 订阅设置变化，实时开关鼠标 / 触控板事件捕获（[applyMotionEventSources]）
     * 并收尾右摇杆——设置页开关切换时无需重启无障碍服务即可生效。
     *
     * [AppGraph.containerOrNull] 在 Application 创建时即初始化，这里仍做一次
     * 轮询兜底，避免极端时序下订阅永远丢失。
     */
    private fun startSettingsObserver() {
        settingsJob?.cancel()
        settingsJob = serviceScope.launch {
            var repository = AppGraph.containerOrNull?.settingsRepository
            while (repository == null) {
                delay(200)
                repository = AppGraph.containerOrNull?.settingsRepository
            }
            repository.settings.collect { settings ->
                applyMotionEventSources(settings)
                if (!settings.gamepadEnabled || !settings.gamepadMouseStickEnabled) {
                    // 功能中途关闭：停掉闲置计时并把可能按住的右摇杆收回中心。
                    mainHandler.removeCallbacks(mouseStickIdle)
                    mouseDeflection = null
                    syncSticks()
                }
                if (!settings.gamepadEnabled || !settings.gamepadKeyboardEnabled) {
                    // 键盘映射中途关闭：此前按住的长按键再也收不到 UP 回调，
                    // 全部收笔抬起，避免指针卡在屏幕上。
                    releaseHeldKeys()
                }
            }
        }
    }

    /**
     * 按设置请求 / 释放鼠标、触控板事件来源（API 34+，其余版本为无操作）。
     *
     * 只有「手柄模拟 + 鼠标/触控板右摇杆 + 右摇杆中心已标定」三者齐备才捕获——
     * 半开状态捕获只会白白吞掉指针事件。**绝不**在这里请求 SOURCE_TOUCHSCREEN。
     */
    private fun applyMotionEventSources(settings: AppSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val mappings = settings.gamepadMappingsFor(settings.gamepadMode)
        val capture = settings.gamepadEnabled &&
            settings.gamepadMouseStickEnabled &&
            mappings.rightStickCenterConfigured
        val want = if (capture) {
            InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_TOUCHPAD
        } else {
            0
        }
        val info = serviceInfo ?: return
        if (runCatching { info.motionEventSources }.getOrNull() == want) return
        serviceInfo = info.apply { motionEventSources = want }
        Log.i(AutoRunnerApplication.TAG, "motionEventSources updated to $want")
    }

    /**
     * 键盘输入映射（仅在设置里开启「手柄模拟」时生效）。
     *
     * 三条互斥的路径：
     * * **WASD 左摇杆**（额外开启「WASD 控制左摇杆」且摇杆中心已标定时）：
     *   只依赖手柄总开关，不依赖「键盘映射」——手机外接实体键盘也能用；
     *   W/A/S/D 合成方向并按住注入连续手势，吞掉 DOWN/UP 全部键事件；
     * * **长按按键**（命中已绑定且开启「长按」的键位时）：按下即注入
     *   `willContinue` 笔画保持指针按住，物理键抬起才收笔抬起；按住期间
     *   该键的自动重复 DOWN 与 UP 全部吞掉；
     * * **逐键点击**（需要「键盘映射」开关）：命中已绑定键位时，在标定坐标上
     *   排队派发一次点击手势并返回 `true` 吞掉本次按键。
     *
     * 其余所有情况返回 `false`，按键照常分发给前台应用——因此开关关闭时
     * 实体键盘行为完全不受影响。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val settings = AppGraph.containerOrNull?.settingsRepository?.current ?: AppSettings.Default
        if (!settings.gamepadEnabled) {
            // 总开关中途关闭时，清掉可能残留的按住状态（此前按下的 WASD
            // 与长按键再也收不到 UP 回调），把已注入的指针收回摇杆中心。
            releaseStickIfHeld()
            releaseHeldKeys()
            return false
        }

        // ------------------------------------------------------ WASD 左摇杆
        if (settings.gamepadKeyboardStickEnabled && event.keyCode in GamepadStickKeys.All) {
            val mappings = settings.gamepadMappingsFor(settings.gamepadMode)
            if (!mappings.stickCenterConfigured) {
                // 未标定摇杆中心：无法计算方向位置，按键原样放行。
                releaseStickIfHeld()
                return false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> heldStickKeys.add(event.keyCode) // 幂等，自动重复无副作用
                KeyEvent.ACTION_UP -> heldStickKeys.remove(event.keyCode)
                else -> return false
            }
            syncSticks()
            return true
        }
        // 摇杆开关已关但此前按住过 WASD：补一次收尾，避免指针卡在屏幕上。
        releaseStickIfHeld()

        // --------------------------------------------- 逐键点击 / 长按输入
        // 以下路径仍需「键盘映射」总开关；未开启时按键照常放行，
        // 并把可能按住的长按键收掉。
        if (!settings.gamepadKeyboardEnabled) {
            releaseHeldKeys()
            return false
        }
        val mapping = settings.gamepadMappingsFor(settings.gamepadMode).buttons
            .firstOrNull { it.configured && it.keyCode == event.keyCode }
        // 长按键在按住期间独占该键的全部事件：即使映射中途被改动 / 解绑，
        // UP 也必须走这里收笔，不能漏给前台应用（前台从未收到过 DOWN）。
        val heldSlot = heldKeySlots[event.keyCode]
        if (mapping?.holdMode == true || heldSlot != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // 只认首次按下；按住期间的自动重复 DOWN 全部吞掉。
                    if (event.repeatCount == 0 && mapping?.holdMode == true) {
                        val slot = heldKeySlots.getOrPut(event.keyCode) { KeySlot() }
                        slot.desired = true
                        slot.x = mapping.x
                        slot.y = mapping.y
                        syncSticks()
                    }
                }

                KeyEvent.ACTION_UP -> {
                    heldSlot?.let {
                        it.desired = false
                        syncSticks()
                    }
                }

                else -> return false
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (mapping == null) return false

        // 一次性点击：进入统一派发队列（而非直接 dispatchGesture），
        // 避免打断在途的长按 / 摇杆按住手势；执行中则排队到完成后再派发。
        pendingKeyTaps += PendingKeyTap(mapping.x, mapping.y, mapping.durationMs)
        syncSticks()
        return true
    }

    /**
     * 鼠标 / 触控板 → 右摇杆（仅在 [applyMotionEventSources] 捕获期间回调，
     * 且被请求来源的事件不会下发给系统——「把鼠标当成右摇杆」即此语义）。
     *
     * 每次移动取「本事件与上一事件的位置差 × [MOUSE_STICK_GAIN]」作为推杆向量
     * 并夹到摇杆半径内：指针移得越快推杆越大；停移 [MOUSE_STICK_IDLE_MS] 毫秒
     * 后由 [mouseStickIdle] 收笔回中抬起。首条事件只记录基准点，避免开瞬间跳变。
     */
    override fun onMotionEvent(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                val last = lastMousePoint
                lastMousePoint = PointF(event.x, event.y)
                if (last == null) return
                val settings = AppGraph.containerOrNull?.settingsRepository?.current ?: AppSettings.Default
                if (!settings.gamepadEnabled || !settings.gamepadMouseStickEnabled) return
                val mappings = settings.gamepadMappingsFor(settings.gamepadMode)
                if (!mappings.rightStickCenterConfigured) return
                var dx = (event.x - last.x) * MOUSE_STICK_GAIN
                var dy = (event.y - last.y) * MOUSE_STICK_GAIN
                val len = hypot(dx, dy)
                if (len > mappings.stickRadius && len > 0f) {
                    dx = dx * mappings.stickRadius / len
                    dy = dy * mappings.stickRadius / len
                }
                mouseDeflection = PointF(dx, dy)
                syncSticks()
                mainHandler.removeCallbacks(mouseStickIdle)
                mainHandler.postDelayed(mouseStickIdle, MOUSE_STICK_IDLE_MS)
            }

            MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_CANCEL -> {
                lastMousePoint = null
                mainHandler.removeCallbacks(mouseStickIdle)
                if (mouseDeflection != null) {
                    mouseDeflection = null
                    syncSticks()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 双摇杆状态机
    //
    // 左（WASD 键盘）与右（鼠标 / 触控板）两个槽位共用一套手势派发：
    // * 按住语义靠 GestureDescription 的「续笔画」实现：willContinue=true 的笔画
    //   在手势完成后**指针保持按下**，因此一次短手势即可无限期按住摇杆；
    //   新派发的多笔画手势只包含本次有变化的槽位，不打断另一侧的已按住指针；
    // * 一段手势执行期间到达的任何变化只排队（stickDispatchPending），因为
    //   执行中的手势会取消在途手势，抢先派发会把指针直接打飞；
    // * 手势被取消（用户触摸屏幕 / 其他手势抢占）时双侧指针都可能丢失，
    //   全部清空后由 syncSticks 按需从中心重新按下。
    //
    // 全部状态只在主线程访问：onKeyEvent / onMotionEvent 与手势回调
    // （handler=null）都跑在主 Looper。

    /** 一个摇杆侧的注入状态（左 = WASD，右 = 鼠标 / 触控板）。 */
    private class StickSlot {
        /** 最近派发的笔画；非 `null` 表示指针处于按下状态且该笔画可被继续。 */
        var stroke: GestureDescription.StrokeDescription? = null

        /** 指针当前位置（上一笔画终点，续笔画的新 path 必须从这里开始）。 */
        var point: PointF? = null

        /** 最近一次同步时的标定快照，供该侧中途关闭时收笔使用。 */
        var snapshot: GamepadMappings? = null

        /** 在途笔画是该侧的收尾（回中抬起）笔画。 */
        var releasing = false

        fun reset() {
            stroke = null
            point = null
            releasing = false
        }
    }

    /** 当前按住的 WASD 键码集合。 */
    private val heldStickKeys = mutableSetOf<Int>()

    /** 一个长按按键的注入状态（按住到物理键抬起才收笔）。 */
    private class KeySlot {
        /** 最近派发的笔画；非 `null` 表示指针处于按下状态且该笔画可被继续。 */
        var stroke: GestureDescription.StrokeDescription? = null

        /** 指针当前位置（收笔续画必须从这里开始）。 */
        var point: PointF? = null

        /** 物理键当前仍被按住（期望指针保持按下）。 */
        var desired = false

        /** 注入坐标（按下瞬间的映射快照，中途改映射不影响在途按住）。 */
        var x = 0f
        var y = 0f

        /** 在途笔画是该键的收笔（抬起）笔画。 */
        var releasing = false

        fun reset() {
            stroke = null
            point = null
            releasing = false
        }
    }

    /** 排队等待下一段手势的一次性点击（非长按键的固定时长点击）。 */
    private data class PendingKeyTap(val x: Float, val y: Float, val durationMs: Long)

    /** 长按键槽位：键码 → 注入状态。 */
    private val heldKeySlots = mutableMapOf<Int, KeySlot>()

    /** 已入队、尚未随手势派发的一次性点击。 */
    private val pendingKeyTaps = mutableListOf<PendingKeyTap>()

    /** 左摇杆槽位（WASD）。 */
    private val leftStick = StickSlot()

    /** 右摇杆槽位（鼠标 / 触控板）。 */
    private val rightStick = StickSlot()

    /** 鼠标当前推杆向量（相对右摇杆中心的像素偏移）；`null` 表示已回中收笔。 */
    private var mouseDeflection: PointF? = null

    /** 鼠标最近一次移动的位置，用于计算位移增量。 */
    private var lastMousePoint: PointF? = null

    /** 一段（可能含双侧笔画的）手势正在执行。 */
    private var stickGestureRunning = false

    /** 手势执行期间状态又变了，完成后需要再同步一次。 */
    private var stickDispatchPending = false

    private val stickGestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            handleStickGestureDone(cancelled = false)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            handleStickGestureDone(cancelled = true)
        }
    }

    /** 鼠标 / 触控板停移超时：推杆回中、指针抬起。 */
    private val mouseStickIdle = Runnable {
        mouseDeflection = null
        syncSticks()
    }

    /** 有残留按住状态时按当前开关状态做一次同步（通常用于收尾）。 */
    private fun releaseStickIfHeld() {
        if (heldStickKeys.isEmpty() && leftStick.point == null && rightStick.point == null) return
        heldStickKeys.clear()
        syncSticks()
    }

    /** 键盘映射中途关闭时收尾：清掉排队点击并把长按键全部标记松开收笔。 */
    private fun releaseHeldKeys() {
        pendingKeyTaps.clear()
        if (heldKeySlots.isEmpty()) return
        heldKeySlots.values.forEach { it.desired = false }
        syncSticks()
    }

    /** 该侧摇杆中心（按下起点 / 收笔回中的目标）；未标定返回 `null`。 */
    private fun centerOf(slot: StickSlot, mappings: GamepadMappings): PointF? {
        val configured = if (slot === rightStick) {
            mappings.rightStickCenterConfigured
        } else {
            mappings.stickCenterConfigured
        }
        if (!configured) return null
        return if (slot === rightStick) {
            PointF(mappings.rightStickCenterX.coerceAtLeast(0f), mappings.rightStickCenterY.coerceAtLeast(0f))
        } else {
            PointF(mappings.stickCenterX.coerceAtLeast(0f), mappings.stickCenterY.coerceAtLeast(0f))
        }
    }

    /**
     * 按最新设置把双侧状态同步成屏幕上的摇杆位置。
     *
     * 某侧开关关闭或摇杆中心未标定时走收尾路径：方向视为回中，
     * 用该侧最近一次标定快照把指针滑回中心并抬起。
     */
    private fun syncSticks() {
        val settings = AppGraph.containerOrNull?.settingsRepository?.current ?: AppSettings.Default
        val mappings = settings.gamepadMappingsFor(settings.gamepadMode)
        val handoffOn = settings.gamepadEnabled
        val leftOpen = handoffOn && settings.gamepadKeyboardStickEnabled
        val rightOpen = handoffOn && settings.gamepadMouseStickEnabled
        if (leftOpen && mappings.stickCenterConfigured) leftStick.snapshot = mappings
        if (rightOpen && mappings.rightStickCenterConfigured) rightStick.snapshot = mappings

        val leftTarget = if (leftOpen && mappings.stickCenterConfigured) {
            GamepadStickKeys.directionOf(heldStickKeys)?.let { (dx, dy) ->
                PointF(
                    (mappings.stickCenterX + mappings.stickRadius * dx).coerceAtLeast(0f),
                    (mappings.stickCenterY + mappings.stickRadius * dy).coerceAtLeast(0f),
                )
            }
        } else {
            null
        }
        val rightTarget = if (rightOpen && mappings.rightStickCenterConfigured) {
            mouseDeflection?.let { d ->
                PointF(
                    (mappings.rightStickCenterX + d.x).coerceAtLeast(0f),
                    (mappings.rightStickCenterY + d.y).coerceAtLeast(0f),
                )
            }
        } else {
            null
        }
        dispatchSticks(leftTarget, rightTarget)
    }

    /**
     * 为该侧构造本次手势要包含的笔画并更新槽位状态。
     *
     * @return `null` 表示该侧本次无变化（不参与手势）或构造失败（已重置槽位）。
     */
    private fun slotStroke(slot: StickSlot, target: PointF?): GestureDescription.StrokeDescription? {
        val snapshot = slot.snapshot
        val current = slot.point
        if (current == null) {
            // 首次按下：从该侧摇杆中心滑向目标点（真实摇杆的“推起”过程）。
            if (target == null || snapshot == null) return null
            val center = centerOf(slot, snapshot) ?: return null
            val stroke = runCatching {
                val path = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(target.x, target.y)
                }
                GestureDescription.StrokeDescription(path, 0L, STICK_MOVE_DURATION_MS, true)
            }.getOrElse {
                slot.reset()
                return null
            }
            slot.stroke = stroke
            slot.point = target
            slot.releasing = false
            return stroke
        }
        if (snapshot == null) {
            slot.reset()
            return null
        }
        val releasing = target == null
        val end = if (releasing) {
            centerOf(slot, snapshot) ?: run {
                slot.reset()
                return null
            }
        } else {
            target
        }
        if (end == current) return null // 位置没变（重复同步），指针保持原状
        val base = slot.stroke ?: run {
            slot.reset()
            return null
        }
        val stroke = runCatching {
            val path = Path().apply {
                moveTo(current.x, current.y)
                lineTo(end.x, end.y)
            }
            base.continueStroke(
                path,
                0L,
                if (releasing) STICK_RELEASE_DURATION_MS else STICK_MOVE_DURATION_MS,
                !releasing,
            )
        }.getOrElse {
            slot.reset()
            return null
        }
        slot.stroke = stroke
        slot.point = end
        slot.releasing = releasing
        return stroke
    }

    /**
     * 为长按键构造本次手势要包含的笔画并更新槽位状态。
     *
     * @return `null` 表示该键本次无变化（不参与手势）或构造失败（已重置槽位）。
     */
    private fun keyStroke(slot: KeySlot): GestureDescription.StrokeDescription? {
        val current = slot.point
        if (current == null) {
            // 首次按下：在映射坐标上注入 willContinue 笔画，指针保持按下。
            if (!slot.desired) return null
            val stroke = runCatching {
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(slot.x, slot.y) },
                    0L,
                    STICK_MOVE_DURATION_MS,
                    true,
                )
            }.getOrElse {
                slot.reset()
                return null
            }
            slot.stroke = stroke
            slot.point = PointF(slot.x, slot.y)
            slot.releasing = false
            return stroke
        }
        if (slot.desired) return null // 仍按住：指针保持原状，无变化
        val base = slot.stroke ?: run {
            slot.reset()
            return null
        }
        // 物理键抬起：原地收笔，指针随之抬起。
        val stroke = runCatching {
            base.continueStroke(
                Path().apply { moveTo(current.x, current.y) },
                0L,
                STICK_RELEASE_DURATION_MS,
                false,
            )
        }.getOrElse {
            slot.reset()
            return null
        }
        slot.stroke = stroke
        slot.releasing = true
        return stroke
    }

    /** 计算双侧目标并派发一段手势；执行中则排队到完成回调里。 */
    private fun dispatchSticks(leftTarget: PointF?, rightTarget: PointF?) {
        if (stickGestureRunning) {
            stickDispatchPending = true
            return
        }
        // 单次手势只携带本次有变化的笔画；另一侧（及已按住的长按键）
        // 已按住的指针不受影响。
        val strokes = mutableListOf<GestureDescription.StrokeDescription>()
        slotStroke(leftStick, leftTarget)?.let(strokes::add)
        slotStroke(rightStick, rightTarget)?.let(strokes::add)
        heldKeySlots.values.forEach { slot -> keyStroke(slot)?.let(strokes::add) }
        pendingKeyTaps.forEach { tap ->
            strokes += GestureDescription.StrokeDescription(
                Path().apply { moveTo(tap.x, tap.y) },
                0L,
                tap.durationMs.coerceAtLeast(MIN_KEY_STROKE_DURATION_MS),
            )
        }
        pendingKeyTaps.clear()
        if (strokes.isEmpty()) return
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }
        stickGestureRunning = true
        stickDispatchPending = false
        val accepted = runCatching { dispatchGesture(builder.build(), stickGestureCallback, null) }
            .getOrDefault(false)
        if (!accepted) handleStickGestureDone(cancelled = true)
    }

    /** 一段手势结束：按需清理指针状态并处理排队的同步请求。 */
    private fun handleStickGestureDone(cancelled: Boolean) {
        stickGestureRunning = false
        if (cancelled) {
            // 取消（用户触摸屏幕 / 其他手势抢占）会抬起双侧指针；若仍需要
            // 按住，下面的同步会从中心重新按下。清空后正常收笔标记一并复位。
            leftStick.reset()
            rightStick.reset()
            heldKeySlots.values.forEach { it.reset() }
            stickDispatchPending = true
        } else {
            // 正常完成：收笔侧指针已抬起，清理其状态；按住侧保持续笔能力。
            if (leftStick.releasing) leftStick.reset()
            if (rightStick.releasing) rightStick.reset()
            heldKeySlots.values.forEach { if (it.releasing) it.reset() }
        }
        // 长按键仍被物理按住但指针已不在（取消 / 刚收完笔又重新按下）：
        // 排队重新按下，让按住状态追上物理键。
        if (heldKeySlots.values.any { it.desired && it.point == null }) {
            stickDispatchPending = true
        }
        // 清掉已完全松开的槽位，避免按键状态无限累积。
        heldKeySlots.entries.removeAll { !it.value.desired && it.value.point == null }
        if (stickDispatchPending) {
            stickDispatchPending = false
            syncSticks()
        }
    }

    /**
     * 挂载透明采集层（§6.1.1）。
     *
     * 该层是类型为 `TYPE_ACCESSIBILITY_OVERLAY` 的普通窗口，因此窗口寻址
     * 依然有效：浮动控制面板位于它之上并保持可点击，
     * 用户正是通过它来停止会话。
     *
     * @param onStopRequested 当用户点击绘制的停止控件时调用。
     * @param isSelfInjected AutoRunner 正在注入镜像手势时为 `true`。
     * @return 当该层已被安排挂载时返回 `true`。
     */
    fun attachTouchCapture(
        listener: (RawTouchEvent) -> Unit,
        onStopRequested: () -> Unit,
        isSelfInjected: () -> Boolean,
        controlLabel: String = TouchCaptureView.STOP_LABEL,
    ): Boolean {
        touchListener = listener
        if (captureView != null) return true
        val windowManager = getSystemService(WindowManager::class.java) ?: return false
        return runCatching {
            mainHandler.post {
                if (captureView != null) return@post
                val view = TouchCaptureView(
                    context = this,
                    listener = { event -> touchListener?.invoke(event) },
                    onStopRequested = onStopRequested,
                    isSelfInjected = isSelfInjected,
                    controlLabel = controlLabel,
                )
                runCatching {
                    windowManager.addView(view, TouchCaptureView.layoutParams())
                    captureView = view
                }.onFailure { error ->
                    Log.w(AutoRunnerApplication.TAG, "unable to attach capture layer", error)
                }
            }
            true
        }.getOrDefault(false)
    }

    /** 移除采集层；在没有挂载任何内容时调用也是安全的。 */
    fun detachTouchCapture() {
        touchListener = null
        val view = captureView ?: return
        captureView = null
        mainHandler.post {
            runCatching {
                getSystemService(WindowManager::class.java)?.removeView(view)
            }
        }
    }

    /** 采集层正在消费触摸时为 `true`。 */
    val isCapturing: Boolean get() = captureView != null

    /**
     * 临时让采集层不可触摸。
     *
     * 采集层是全屏且可触摸的最上层窗口，注入的镜像手势会先打到它自己身上，
     * 结果就是「用户点了屏幕，下面的真实应用毫无反应」。注入期间关掉触摸
     * （FLAG_NOT_TOUCHABLE），手势才会落到真实应用；注入结束后立即恢复。
     */
    fun setCaptureTouchable(touchable: Boolean) {
        val view = captureView ?: return
        mainHandler.post {
            runCatching {
                val params = view.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
                params.flags = if (touchable) {
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                getSystemService(WindowManager::class.java)?.updateViewLayout(view, params)
            }
        }
    }

    /**
     * 捕获下一次抬起的点击并报告其坐标。
     *
     * 供手柄映射界面使用（“拾取坐标”）。用户手指抬起后，
     * 临时采集层会被移除，且不会影响会话，因为录制进行时会拒绝拾取。
     */
    fun captureNextPoint(onCaptured: (Float, Float) -> Unit): Boolean {
        if (captureView != null) return false
        return attachTouchCapture(
            listener = { event ->
                if (event.phase == TouchPhase.UP) {
                    event.samples.firstOrNull()?.let { sample ->
                        detachTouchCapture()
                        onCaptured(sample.x, sample.y)
                    }
                }
            },
            onStopRequested = { detachTouchCapture() },
            isSelfInjected = { false },
            controlLabel = TouchCaptureView.CANCEL_LABEL,
        )
    }

    /** 中止正在执行的手势（由“停止”控件使用）。 */
    fun abortCurrentGesture() = Unit

    private companion object {
        /** 键盘映射点击手势的最短笔画时长；低于该值系统可能丢弃手势。 */
        const val MIN_KEY_STROKE_DURATION_MS = 20L

        /** WASD 摇杆一次移动（按下推出 / 方向切换）的笔画时长。 */
        const val STICK_MOVE_DURATION_MS = 60L

        /** 摇杆收笔（滑回中心并抬起）的笔画时长。 */
        const val STICK_RELEASE_DURATION_MS = 80L

        /** 鼠标位移 → 推杆向量的增益（像素位移 × 增益 = 像素偏移，夹到半径内）。 */
        const val MOUSE_STICK_GAIN = 30f

        /** 鼠标 / 触控板停移多久后推杆自动回中抬起（毫秒）。 */
        const val MOUSE_STICK_IDLE_MS = 100L
    }
}
