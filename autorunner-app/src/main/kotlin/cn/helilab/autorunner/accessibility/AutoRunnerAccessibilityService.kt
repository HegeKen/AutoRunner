package cn.helilab.autorunner.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import cn.helilab.autorunner.AutoRunnerApplication
import com.autorunner.core.recording.RawTouchEvent
import com.autorunner.core.recording.TouchPhase

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

    // 注意：这里刻意*不*使用 `AccessibilityServiceInfo.motionEventSources`。
    // 平台文档写明“来自 getMotionEventSources() 中来源的 MotionEvent
    // 不会被发送到系统的其余部分”，也就是说请求 SOURCE_TOUCHSCREEN 会让
    // 本服务吞掉设备上的每一次触摸。在 onServiceConnected() 中这么做，
    // 会在用户授予权限的那一刻让整块屏幕变成砖。
    // 因此触摸采集完全由设计文档 §6.1.1 描述的透明
    // TYPE_ACCESSIBILITY_OVERLAY 层完成，从而保持正常的窗口寻址
    // （以及浮动停止控件）不受影响。

    override fun onServiceConnected() {
        super.onServiceConnected()
        val previous = serviceInfo
        serviceInfo = (previous ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
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
}
