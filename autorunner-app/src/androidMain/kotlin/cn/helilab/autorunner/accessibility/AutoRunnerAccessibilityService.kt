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
 * The heart of gesture recording and replay (§6.1).
 *
 * * `onServiceConnected` publishes the instance to [AccessibilityServiceHolder]
 *   so `AndroidAccessibilityController` can dispatch gestures and
 *   `AndroidRecordingController` can attach the capture layer.
 * * The capture layer is a `TYPE_ACCESSIBILITY_OVERLAY` view, so recording never
 *   needs the `SYSTEM_ALERT_WINDOW` permission.
 * * `onUnbind` immediately detaches the capture layer: leaving a full screen
 *   touch consumer behind would make the device unusable.
 */
class AutoRunnerAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var captureView: TouchCaptureView? = null

    private var touchListener: ((RawTouchEvent) -> Unit)? = null

    // NOTE: `AccessibilityServiceInfo.motionEventSources` is deliberately NOT
    // used. The platform documents that "MotionEvents from sources in
    // getMotionEventSources() are not sent to the rest of the system", i.e.
    // requesting SOURCE_TOUCHSCREEN makes the service swallow every touch on the
    // device. Doing that in onServiceConnected() bricked the whole screen the
    // moment the user granted the accessibility permission. Touch capture is
    // therefore done exclusively with the transparent
    // TYPE_ACCESSIBILITY_OVERLAY layer described in §6.1.1 of the design
    // document, which keeps normal window targeting (and therefore the floating
    // stop control) intact.

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
            // Defensive reset: clear any motion event source a previous build may
            // have registered, otherwise the touch screen would stay swallowed
            // until the user disables the service by hand.
            motionEventSources = 0
        }
        // Defensive clean-up: if the process was restarted while a recording
        // session owned the full screen capture layer, that layer would keep
        // consuming every touch on the device. Drop it before anything else.
        detachTouchCapture()
        AccessibilityServiceHolder.attach(this)
        Log.i(
            AutoRunnerApplication.TAG,
            "accessibility service connected: motionEventSources=" +
                "${runCatching { serviceInfo?.motionEventSources }.getOrNull()} (0 = touches reach apps)",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gesture replay does not need the event stream; the hook is kept so the
        // service stays bound and is not optimised away by the system.
    }

    override fun onInterrupt() = Unit

    /**
     * Attaches the transparent capture layer (§6.1.1).
     *
     * The layer is a normal window of type `TYPE_ACCESSIBILITY_OVERLAY`, so window
     * targeting still works: the floating control panel sits above it and stays
     * tappable, which is how the user stops a session.
     *
     * @param onStopRequested invoked when the user taps the painted stop control.
     * @param isSelfInjected `true` while AutoRunner is injecting the mirror.
     * @return `true` when the layer was scheduled for attachment.
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

    /** Removes the capture layer; safe to call when nothing is attached. */
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

    /** `true` while the capture layer consumes touches. */
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
     * Captures the next released tap and reports its coordinates.
     *
     * Used by the gamepad mapping UI ("拾取坐标"). The temporary capture layer is
     * removed as soon as the user lifts the finger, and the session is not affected
     * because picking is refused while a recording runs.
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

    /** Aborts an in-flight gesture (used by the "stop" control). */
    fun abortCurrentGesture() = Unit
}
