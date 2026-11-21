package cn.helilab.autorunner.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import cn.helilab.autorunner.AutoRunnerApplication
import kotlin.math.roundToInt

/** 任务控制按钮在屏幕上的位置：左「暂停 / 运行」、右「退出熄屏」。 */
internal data class IdleTaskBounds(
    val toggle: IdleActionBounds,
    val exit: IdleActionBounds,
)

/**
 * 黑屏挂机层中、任务进度圆环下方的两个控制按钮。
 *
 * 与媒体按钮相同，黑屏层本身不可触摸，因此每个按钮都是一个只有按钮大小的
 * 独立可触摸窗口，跟随文字面板移动。两个按钮分开成两个窗口而不是一个宽
 * 窗口，避免整条矩形截获下层游戏的注入触摸。
 *
 * 左键控制脚本的暂停 / 继续，右键主动退出熄屏模式；脚本注入期间由
 * [IdleInjectionPassthrough] 临时关闭触摸，与媒体按钮保持一致。
 */
internal class IdleTaskControlWindow(
    private val context: Context,
    private val windowManager: WindowManager?,
    private val windowType: Int,
    private val onTogglePause: () -> Unit,
    private val onExit: () -> Unit,
) {

    private class Slot {
        var view: IdleTaskControlView? = null
        var params: WindowManager.LayoutParams? = null
    }

    private val toggleSlot = Slot()
    private val exitSlot = Slot()

    private var pendingToggle: IdleActionBounds? = null
    private var pendingExit: IdleActionBounds? = null

    private var paused = false
    private var actionEnabled = false
    private var touchable = true

    val isShowing: Boolean get() = toggleSlot.view != null

    /** 添加（或更新）两个按钮；重复调用只同步状态。 */
    fun show(paused: Boolean, enabled: Boolean) {
        this.paused = paused
        actionEnabled = enabled
        val existing = toggleSlot.view
        if (existing != null) {
            existing.paused = paused
            existing.actionEnabled = enabled
            return
        }
        val manager = windowManager ?: return
        val toggleView = IdleTaskControlView(context, Icon.TOGGLE_PLAY_PAUSE).apply {
            this.paused = paused
            actionEnabled = enabled
            onTap = onTogglePause
        }
        val exitView = IdleTaskControlView(context, Icon.EXIT).apply {
            onTap = onExit
        }
        val toggleParams = buildLayoutParams().apply { seedPosition(pendingToggle) }
        val exitParams = buildLayoutParams().apply { seedPosition(pendingExit) }
        val toggleAdded = runCatching { manager.addView(toggleView, toggleParams) }
            .onFailure { Log.w(AutoRunnerApplication.TAG, "task toggle window attach failed", it) }
            .isSuccess
        if (!toggleAdded) return
        val exitAdded = runCatching { manager.addView(exitView, exitParams) }
            .onFailure {
                Log.w(AutoRunnerApplication.TAG, "task exit window attach failed", it)
                runCatching { manager.removeViewImmediate(toggleView) }
            }
            .isSuccess
        if (!exitAdded) return
        toggleSlot.view = toggleView
        toggleSlot.params = toggleParams
        exitSlot.view = exitView
        exitSlot.params = exitParams
        applyTouchable()
    }

    fun hide() {
        val manager = windowManager
        toggleSlot.view?.let { view ->
            toggleSlot.view = null
            toggleSlot.params = null
            runCatching { manager?.removeViewImmediate(view) }
        }
        exitSlot.view?.let { view ->
            exitSlot.view = null
            exitSlot.params = null
            runCatching { manager?.removeViewImmediate(view) }
        }
    }

    /** 跟随文字面板移动；两个按钮分别按像素逐帧提交。 */
    fun moveTo(bounds: IdleTaskBounds) {
        pendingToggle = bounds.toggle
        pendingExit = bounds.exit
        updatePosition(toggleSlot, bounds.toggle)
        updatePosition(exitSlot, bounds.exit)
    }

    /** 脚本注入期间让出触摸，结束后恢复真实手指可点。 */
    fun setTouchable(touchable: Boolean) {
        this.touchable = touchable
        applyTouchable()
    }

    private fun applyTouchable() {
        applyTouchable(toggleSlot)
        applyTouchable(exitSlot)
    }

    private fun applyTouchable(slot: Slot) {
        val view = slot.view ?: return
        val params = slot.params ?: return
        val flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags == params.flags) return
        params.flags = flags
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun updatePosition(slot: Slot, bounds: IdleActionBounds) {
        val view = slot.view ?: return
        val params = slot.params ?: return
        val x = bounds.left.roundToInt()
        val y = bounds.top.roundToInt()
        if (params.x == x && params.y == y) return
        params.x = x
        params.y = y
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun WindowManager.LayoutParams.seedPosition(bounds: IdleActionBounds?) {
        val metrics = context.resources.displayMetrics
        x = if (bounds != null) bounds.left.roundToInt() else metrics.widthPixels
        y = if (bounds != null) bounds.top.roundToInt() else metrics.heightPixels
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    companion object {
        /** 单个任务按钮的直径（dp）；文字面板按它预留行高与左右位置。 */
        const val SIZE_DP = 36f
    }
}

private enum class Icon {
    TOGGLE_PLAY_PAUSE,
    EXIT,
}

/** 单个圆形任务控制按钮：暂停 / 继续，或退出熄屏。 */
@SuppressLint("ViewConstructor")
private class IdleTaskControlView(
    context: Context,
    private val icon: Icon,
) : View(context) {

    var paused = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 没有进行中的任务时暂停键置灰（退出键始终可用）。 */
    var actionEnabled = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var onTap: (() -> Unit)? = null

    private val buttonSize = dp(IdleTaskControlWindow.SIZE_DP)

    private var pressed = false

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(222, 26, 26, 26)
        style = Paint.Style.FILL
    }

    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 68, 68, 68)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 245, 245, 245)
        style = Paint.Style.FILL
    }

    private val disabledIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(76, 220, 220, 220)
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
    }

    private val iconPath = Path()

    private val arcRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(buttonSize.roundToInt(), buttonSize.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val center = buttonSize / 2f
        val paint = if (actionEnabled) iconPaint else disabledIconPaint
        canvas.drawCircle(center, center, buttonSize / 2f, if (pressed) pressedPaint else backgroundPaint)
        canvas.drawCircle(center, center, buttonSize / 2f - dp(0.5f), borderPaint)
        when (icon) {
            Icon.TOGGLE_PLAY_PAUSE -> {
                if (paused) {
                    drawPlayIcon(canvas, center, center, paint)
                } else {
                    drawPauseIcon(canvas, center, center, paint)
                }
            }

            Icon.EXIT -> drawPowerIcon(canvas, center, center, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!actionEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasPressed = pressed
                pressed = false
                invalidate()
                if (wasPressed) {
                    performClick()
                    onTap?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawPlayIcon(canvas: Canvas, centerX: Float, centerY: Float, paint: Paint) {
        val size = dp(12f)
        iconPath.reset()
        iconPath.moveTo(centerX - size * 0.34f, centerY - size * 0.5f)
        iconPath.lineTo(centerX + size * 0.52f, centerY)
        iconPath.lineTo(centerX - size * 0.34f, centerY + size * 0.5f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
    }

    private fun drawPauseIcon(canvas: Canvas, centerX: Float, centerY: Float, paint: Paint) {
        val height = dp(13f)
        val barWidth = dp(3f)
        val halfGap = dp(2.2f)
        canvas.drawRect(
            centerX - halfGap - barWidth,
            centerY - height / 2f,
            centerX - halfGap,
            centerY + height / 2f,
            paint,
        )
        canvas.drawRect(
            centerX + halfGap,
            centerY - height / 2f,
            centerX + halfGap + barWidth,
            centerY + height / 2f,
            paint,
        )
    }

    /** 电源符号：顶部留口的圆环 + 中轴竖线，表达「退出 / 关闭」。 */
    private fun drawPowerIcon(canvas: Canvas, centerX: Float, centerY: Float, paint: Paint) {
        val radius = dp(6.5f)
        arcRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        strokePaint.color = paint.color
        canvas.drawArc(arcRect, -55f, 290f, false, strokePaint)
        canvas.drawLine(
            centerX,
            centerY - radius - dp(1f),
            centerX,
            centerY - dp(1.5f),
            strokePaint,
        )
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
