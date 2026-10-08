package cn.helilab.autorunner.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.autorunner.core.recording.RawTouchEvent

/**
 * Full screen transparent capture layer (§6.1.1).
 *
 * It is added by [AutoRunnerAccessibilityService] with
 * `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`, which needs no
 * additional `SYSTEM_ALERT_WINDOW` permission.
 *
 * ## Why the stop control lives *inside* this view
 *
 * The layer has to consume touches — that is how the gesture stream is captured —
 * so every window below it (AutoRunner's own activity and, depending on the ROM's
 * window layering, the floating panel) becomes unreachable while a session runs.
 * Relying on a separate overlay window to stop the recording is therefore
 * fragile: on a real MIUI device the "停止录制" tap was swallowed by this layer and
 * even recorded as a script action, leaving no way out.
 *
 * The control is consequently painted by this view, and its touch region is
 * excluded from capture: a gesture that starts inside the control area stops the
 * session and never becomes an action.
 *
 * ## Touch mirroring
 *
 * Because the layer consumes the touches, [AndroidRecordingController] mirrors
 * every *classified* gesture back through `dispatchGesture` so the app underneath
 * still reacts. Frames produced by that mirror are filtered there (input device +
 * in-flight guard) so the recorder cannot feed itself.
 */
@SuppressLint("ViewConstructor")
class TouchCaptureView(
    context: Context,
    private val listener: (RawTouchEvent) -> Unit,
    /** Invoked when the user taps the painted stop control. */
    private val onStopRequested: () -> Unit,
    /** `true` while AutoRunner itself is injecting a gesture (the mirror). */
    private val isSelfInjected: () -> Boolean = { false },
    /** Text of the painted control ("停止录制" while recording, "取消拾取" while picking). */
    private val controlLabel: String = STOP_LABEL,
) : View(context) {

    private val density = resources.displayMetrics.density

    private val controlWidth = 116f * density

    private val controlHeight = 46f * density

    private val controlMargin = 16f * density

    /** Kept clear of the status bar / notch. */
    private val controlTop = 56f * density

    private val controlRect = RectF()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 18, 18, 18)
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4D4F")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
    }

    /** `true` while the current gesture belongs to the stop control. */
    private var controlGesture = false

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        controlRect.set(
            controlMargin,
            controlTop,
            controlMargin + controlWidth,
            controlTop + controlHeight,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (controlRect.isEmpty) return
        val radius = controlRect.height() / 2f
        canvas.drawRoundRect(controlRect, radius, radius, backgroundPaint)

        val dotRadius = 6f * density
        val dotCenterX = controlRect.left + radius * 0.75f
        val dotCenterY = controlRect.centerY()
        canvas.drawCircle(dotCenterX, dotCenterY, dotRadius, dotPaint)

        val textX = dotCenterX + dotRadius + 8f * density
        val textY = controlRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(controlLabel, textX, textY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val insideControl = controlRect.contains(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A mirrored (self injected) gesture must never trigger the
                // control, otherwise a recorded tap at this spot would stop the
                // session by itself.
                controlGesture = insideControl && !isSelfInjected()
                if (controlGesture) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onStopRequested()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasControl = controlGesture
                controlGesture = false
                if (wasControl) return true
            }
        }

        if (controlGesture) {
            // Swallow the whole control gesture: it is UI, not a script action.
            return true
        }

        event.toRawTouchEvent()?.let(listener)
        return true
    }

    companion object {
        /** Label painted on the stop control while recording. */
        const val STOP_LABEL = "停止录制"

        /** Label used while picking a coordinate or a gesture in the editor. */
        const val CANCEL_LABEL = "取消拾取"

        /** Size of the control in dp, exposed so the debug channel can hit it. */
        const val CONTROL_MARGIN_DP = 16
        const val CONTROL_TOP_DP = 56
        const val CONTROL_WIDTH_DP = 108
        const val CONTROL_HEIGHT_DP = 46

        /** Window manager parameters used to attach the capture layer. */
        fun layoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // This window is intentionally the touch target for the whole screen,
            // so it does NOT set FLAG_NOT_TOUCH_MODAL: it must keep receiving a
            // gesture that starts on it and drifts outside its bounds.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
