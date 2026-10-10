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
 * 全屏透明采集层（§6.1.1）。
 *
 * 它由 [AutoRunnerAccessibilityService] 以
 * `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` 添加，无需额外的
 * `SYSTEM_ALERT_WINDOW` 权限。
 *
 * ## 为什么停止控件位于该视图*内部*
 *
 * 该层必须消费触摸事件——这正是采集手势流的方式——
 * 因此它下方的所有窗口（AutoRunner 自己的 Activity，以及视 ROM 窗口层级而定的
 * 浮动面板）在会话运行期间都无法再被触达。
 * 所以依赖独立的覆盖窗口来停止录制并不可靠：在真实的 MIUI 设备上，“停止录制”的点击
 * 会被这一层吞掉，甚至被记录成脚本动作，导致无路可退。
 *
 * 因此停止控件由本视图绘制，其触摸区域被排除在采集之外：
 * 从控件区域内开始的手势会终止会话，且绝不会被记录为动作。
 *
 * ## 触摸镜像
 *
 * 由于该层消费了触摸事件，[AndroidRecordingController] 会把每一个*已分类*的手势
 * 通过 `dispatchGesture` 镜像回去，使下层应用仍能做出响应。
 * 镜像产生的帧会在那里被过滤（输入设备 + 进行中守卫），
 * 以免录制器自我喂入。
 */
@SuppressLint("ViewConstructor")
class TouchCaptureView(
    context: Context,
    private val listener: (RawTouchEvent) -> Unit,
    /** 当用户点击绘制的停止控件时调用。 */
    private val onStopRequested: () -> Unit,
    /** 当 AutoRunner 自己正在注入手势（镜像）时为 `true`。 */
    private val isSelfInjected: () -> Boolean = { false },
    /** 绘制在控件上的文本（录制时为“停止录制”，拾取时为“取消拾取”）。 */
    private val controlLabel: String = STOP_LABEL,
) : View(context) {

    private val density = resources.displayMetrics.density

    private val controlWidth = 116f * density

    private val controlHeight = 46f * density

    private val controlMargin = 16f * density

    /** 避开状态栏 / 刘海区域。 */
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

    /** 当前手势属于停止控件时为 `true`。 */
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
                // 镜像（自注入）的手势绝不能触发该控件，
                // 否则在此位置录制的点击会自行停止会话。
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
            // 吞掉整个控件手势：它是 UI，不是脚本动作。
            return true
        }

        event.toRawTouchEvent()?.let(listener)
        return true
    }

    companion object {
        /** 录制时绘制在停止控件上的标签。 */
        const val STOP_LABEL = "停止录制"

        /** 在编辑器中拾取坐标或手势时使用的标签。 */
        const val CANCEL_LABEL = "取消拾取"

        /** 控件尺寸（dp），对外暴露以便调试通道能够命中它。 */
        const val CONTROL_MARGIN_DP = 16
        const val CONTROL_TOP_DP = 56
        const val CONTROL_WIDTH_DP = 108
        const val CONTROL_HEIGHT_DP = 46

        /** 用于挂载采集层的窗口管理器参数。 */
        fun layoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 该窗口有意成为整屏的触摸目标，
            // 因此不设置 FLAG_NOT_TOUCH_MODAL：它必须继续接收
            // 从其上方开始并漂移到边界之外的手势。
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
