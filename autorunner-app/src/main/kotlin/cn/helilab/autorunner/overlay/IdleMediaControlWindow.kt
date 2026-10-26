package cn.helilab.autorunner.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import cn.helilab.autorunner.AutoRunnerApplication
import cn.helilab.autorunner.notification.IdleMediaState
import kotlin.math.roundToInt

/** 黑屏挂机层上可点按的媒体动作。 */
internal enum class IdleMediaAction {
    PREVIOUS,
    TOGGLE_PLAY_PAUSE,
    NEXT,
}

/** 滚动文字块内按钮行在屏幕上的位置（像素，含状态栏 / 刘海坐标系）。 */
internal data class IdleActionBounds(val left: Float, val top: Float)

/**
 * 黑屏挂机层的媒体控制按钮窗口。
 *
 * 黑屏层本身是 `FLAG_NOT_TOUCHABLE` 的（触摸必须穿透给下层游戏），因此按钮
 * 不能画在那一层里。这里单独添加一个**只有按钮大小**的可触摸窗口，并让它跟着
 * 滚动文字块移动：触摸落在按钮窗口之外时，`FLAG_NOT_TOUCH_MODAL` 保证事件
 * 继续投递给下层游戏，脚本注入的点击只有压到按钮矩形时才会被拦截。
 *
 * 窗口在「本次挂机期间播放过音乐」时由 [IdleScreenOverlay] 添加；暂停后保留，
 * 会话彻底消失后立即移除。
 */
internal class IdleMediaControlWindow(
    private val context: Context,
    private val windowManager: WindowManager?,
    private val windowType: Int,
    private val onAction: (IdleMediaAction) -> Unit,
) {

    private var view: IdleMediaControlView? = null

    private var layoutParams: WindowManager.LayoutParams? = null

    /** 最近一次由滚动文字块报告的位置；窗口尚未添加时先缓存。 */
    private var pendingX = Int.MIN_VALUE

    private var pendingY = Int.MIN_VALUE

    val isShowing: Boolean get() = view != null

    /** 添加按钮窗口并更新当前播放状态。 */
    fun show(state: IdleMediaState) {
        val existing = view
        if (existing != null) {
            existing.state = state
            return
        }
        val manager = windowManager ?: return
        val control = IdleMediaControlView(context, onAction).apply { this.state = state }
        val params = buildLayoutParams().apply {
            // 首次布局完成前可能还拿不到文字块位置：先放到屏幕外，等下一帧
            // moveTo() 再移入可视区，避免按钮先在左上角闪一下。
            val metrics = context.resources.displayMetrics
            x = if (pendingX != Int.MIN_VALUE) pendingX else metrics.widthPixels
            y = if (pendingY != Int.MIN_VALUE) pendingY else metrics.heightPixels
        }
        runCatching { manager.addView(control, params) }
            .onFailure { error ->
                Log.w(AutoRunnerApplication.TAG, "media control window attach failed", error)
            }
            .onSuccess {
                view = control
                layoutParams = params
            }
    }

    /** 移除按钮窗口；没有添加过时调用也是安全的。 */
    fun hide() {
        val current = view ?: return
        view = null
        layoutParams = null
        runCatching { windowManager?.removeViewImmediate(current) }
    }

    /** 跟随滚动文字块移动；位置按像素变化逐帧提交。 */
    fun moveTo(bounds: IdleActionBounds) {
        val x = bounds.left.roundToInt()
        val y = bounds.top.roundToInt()
        pendingX = x
        pendingY = y
        val control = view ?: return
        val params = layoutParams ?: return
        if (params.x == x && params.y == y) return
        // 面板本身以 60fps 平滑移动。这里若做 40ms 节流，按钮会以 ~20fps
        // 一跳 2~3px 地追赶面板，视觉上就是按钮相对边框来回抖动；小窗口的
        // updateViewLayout 开销可以接受，必须与面板同帧更新。
        params.x = x
        params.y = y
        runCatching { windowManager?.updateViewLayout(control, params) }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            // NOT_TOUCH_MODAL 是黑屏挂机可用性的关键：窗口矩形之外的触摸
            // 继续投递给下层的游戏窗口，只有按钮本身会消费事件。
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
}

/**
 * 上一首 / 播放暂停 / 下一首三个圆形按钮。
 *
 * 背景透明，只绘制按钮本身；按下时高亮，播放器不支持的动作用低透明度表示。
 * 视图尺寸固定，[IdleMediaControlWindow] 直接把它作为一个贴合按钮行的窗口。
 */
@SuppressLint("ViewConstructor")
internal class IdleMediaControlView(
    context: Context,
    private val onAction: (IdleMediaAction) -> Unit,
) : View(context) {

    var state: IdleMediaState? = null
        set(value) {
            field = value
            invalidate()
        }

    private val buttonSize = dp(BUTTON_SIZE_DP)

    private val gap = dp(GAP_DP)

    private val contentWidth = buttonSize * BUTTON_COUNT + gap * (BUTTON_COUNT - 1)

    private var pressedIndex = -1

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

    private val iconPath = Path()

    private val centerY = (buttonSize / 2f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 窗口用 WRAP_CONTENT：尺寸完全由三个按钮决定。
        setMeasuredDimension(contentWidth.roundToInt(), buttonSize.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val playback = state
        for (index in 0 until BUTTON_COUNT) {
            val left = index * (buttonSize + gap)
            val centerX = left + buttonSize / 2f
            val enabled = isEnabled(index, playback)
            val fill = if (index == pressedIndex) pressedPaint else backgroundPaint
            val icon = if (enabled) iconPaint else disabledIconPaint
            canvas.drawCircle(centerX, centerY, buttonSize / 2f, fill)
            canvas.drawCircle(centerX, centerY, buttonSize / 2f - dp(0.5f), borderPaint)
            when (index) {
                INDEX_PREVIOUS -> drawSkipIcon(canvas, centerX, centerY, previous = true, paint = icon)
                INDEX_TOGGLE -> {
                    if (playback?.isPlaying == true) {
                        drawPauseIcon(canvas, centerX, centerY, icon)
                    } else {
                        drawPlayIcon(canvas, centerX, centerY, icon)
                    }
                }

                INDEX_NEXT -> drawSkipIcon(canvas, centerX, centerY, previous = false, paint = icon)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = buttonAt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (index < 0) return false
                pressedIndex = index
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val next = buttonAt(event.x, event.y)
                if (next != pressedIndex) {
                    pressedIndex = next
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pressed = pressedIndex
                pressedIndex = -1
                invalidate()
                if (pressed >= 0 && pressed == index && isEnabled(pressed, state)) {
                    performClick()
                    onAction(actionOf(pressed))
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
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

    private fun buttonAt(x: Float, y: Float): Int {
        if (y < 0f || y > buttonSize) return -1
        for (index in 0 until BUTTON_COUNT) {
            val left = index * (buttonSize + gap)
            if (x >= left && x <= left + buttonSize) return index
        }
        return -1
    }

    private fun isEnabled(index: Int, playback: IdleMediaState?): Boolean {
        if (playback == null) return false
        return when (index) {
            INDEX_PREVIOUS -> playback.canSkipPrevious
            INDEX_TOGGLE -> playback.canTogglePlayPause
            INDEX_NEXT -> playback.canSkipNext
            else -> false
        }
    }

    private fun actionOf(index: Int): IdleMediaAction = when (index) {
        INDEX_PREVIOUS -> IdleMediaAction.PREVIOUS
        INDEX_TOGGLE -> IdleMediaAction.TOGGLE_PLAY_PAUSE
        else -> IdleMediaAction.NEXT
    }

    private fun drawPlayIcon(canvas: Canvas, centerX: Float, centerY: Float, paint: Paint) {
        val size = dp(14f)
        iconPath.reset()
        iconPath.moveTo(centerX - size * 0.34f, centerY - size * 0.5f)
        iconPath.lineTo(centerX + size * 0.52f, centerY)
        iconPath.lineTo(centerX - size * 0.34f, centerY + size * 0.5f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
    }

    private fun drawPauseIcon(canvas: Canvas, centerX: Float, centerY: Float, paint: Paint) {
        val height = dp(15f)
        val barWidth = dp(3.5f)
        val halfGap = dp(2.5f)
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

    private fun drawSkipIcon(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        previous: Boolean,
        paint: Paint,
    ) {
        val size = dp(13f)
        // previous=true 时整组图形沿 x 轴镜像，箭头朝左。
        val direction = if (previous) -1f else 1f
        val half = size / 2f
        iconPath.reset()
        // 双三角：第一个三角从 -0.56 指到 -0.06，第二个从 -0.14 指到 +0.36。
        iconPath.moveTo(centerX - direction * size * 0.56f, centerY - half)
        iconPath.lineTo(centerX - direction * size * 0.06f, centerY)
        iconPath.lineTo(centerX - direction * size * 0.56f, centerY + half)
        iconPath.close()
        iconPath.moveTo(centerX - direction * size * 0.14f, centerY - half)
        iconPath.lineTo(centerX + direction * size * 0.36f, centerY)
        iconPath.lineTo(centerX - direction * size * 0.14f, centerY + half)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        // 末尾竖条：⏮ / ⏭ 的「到列表首 / 尾」标记。
        val barCenter = centerX + direction * size * 0.5f
        canvas.drawRect(
            barCenter - size * 0.06f,
            centerY - half,
            barCenter + size * 0.06f,
            centerY + half,
            paint,
        )
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        /** 单个按钮直径（dp）。 */
        const val BUTTON_SIZE_DP = 44f

        /** 按钮间距（dp）。 */
        const val GAP_DP = 8f

        /** 按钮数量：上一首 / 播放暂停 / 下一首。 */
        const val BUTTON_COUNT = 3

        /** 按钮行总宽度（dp），滚动文字块据此为按钮预留位置。 */
        const val WIDTH_DP = BUTTON_SIZE_DP * BUTTON_COUNT + GAP_DP * (BUTTON_COUNT - 1)

        /** 按钮行高度（dp）。 */
        const val HEIGHT_DP = BUTTON_SIZE_DP

        private const val INDEX_PREVIOUS = 0
        private const val INDEX_TOGGLE = 1
        private const val INDEX_NEXT = 2
    }
}
