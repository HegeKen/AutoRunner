package cn.helilab.autorunner.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import cn.helilab.autorunner.AutoRunnerApplication
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * 黑屏挂机覆盖层。
 *
 * 用一层全屏纯黑窗口模拟熄屏，同时让 display 保持 ON：
 * * `FLAG_KEEP_SCREEN_ON` 阻止系统超时熄屏；
 * * `FLAG_NOT_TOUCHABLE` 让脚本注入的触摸继续穿透到下层游戏
 *   （display 为 OFF 时 InputDispatcher 会直接丢弃注入的触摸）；
 * * 窗口内只绘制一段缓慢反弹的文字提示，位置持续变化以避免
 *   AMOLED 长时间显示同一位置的像素造成烧屏。
 *
 * 整屏纯黑，不再为悬浮球预留透明区域：退出挂机统一走「长按音量减键」，
 * 悬浮球与菜单在挂机期间不可见也不可操作。
 */
class IdleScreenOverlay(
    private val context: Context,
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java),
    private val windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
) {

    private var view: DvdHintView? = null

    val isShowing: Boolean get() = view != null

    /** 添加覆盖层；失败（无悬浮窗权限等）时返回 `false`。 */
    fun show(): Boolean {
        if (view != null) return true
        val manager = windowManager ?: return false
        return runCatching {
            val hintView = DvdHintView(context)
            manager.addView(hintView, buildLayoutParams())
            view = hintView
            true
        }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "idle screen overlay attach failed (type=$windowType)", error)
        }.getOrDefault(false)
    }

    fun hide() {
        val current = view ?: return
        view = null
        runCatching { windowManager?.removeViewImmediate(current) }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            // 显式铺满物理屏：MATCH_PARENT 会落在导航条之上，底部留出一条
            // 透出游戏的亮边，黑屏效果就不完整了。
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
            windowType,
            // 触摸穿透是黑屏挂机成立的前提：脚本的点击 / 滑动必须落到下层游戏。
            // KEEP_SCREEN_ON 让 display 保持 ON，注入的触摸才会被正常投递。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            // 整屏纯黑，不透明格式即可。
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
            // 与悬浮球保持一致：铺满含 cutout 的物理屏，避免打孔屏下出现偏移 / letterbox。
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
}

/**
 * 纯黑背景 + DVD 待机 Logo 式反弹文字。
 *
 * 文字块沿随机斜向匀速移动，碰到屏幕四边按轴反向。速度刻意取低
 * （约 18dp/s），既能看清提示，又不会因为移动过快分散注意力。
 */
private class DvdHintView(context: Context) : View(context) {

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 235, 235, 235)
        textSize = sp(24f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 170, 170, 170)
        textSize = sp(13f)
    }

    private val title = "AutoRunner 挂机中"
    private val subtitleLines = listOf(
        "屏幕保亮 · 触摸穿透",
        "长按音量减键退出黑屏",
    )

    private val gap = dp(10f)
    private val lineGap = dp(3f)
    private val edgeInset = dp(24f)
    private val speedPxPerSecond = dp(18f)

    private var blockWidth = 0f
    private var blockHeight = 0f
    private var posX = -1f
    private var posY = -1f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val previous = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            if (previous != 0L) {
                // 限制单帧步长：卡顿或灭屏恢复时文字不会瞬移。
                val deltaSeconds = ((frameTimeNanos - previous) / 1_000_000_000f)
                    .coerceIn(0f, 0.05f)
                advance(deltaSeconds)
                invalidate()
            }
            if (isAttachedToWindow) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val titleMetrics = titlePaint.fontMetrics
        val subtitleMetrics = subtitlePaint.fontMetrics
        blockWidth = max(
            titlePaint.measureText(title),
            subtitleLines.maxOf { subtitlePaint.measureText(it) },
        )
        blockHeight = (titleMetrics.descent - titleMetrics.ascent) + gap +
            subtitleLines.size * (subtitleMetrics.descent - subtitleMetrics.ascent) +
            (subtitleLines.size - 1) * lineGap
        if (posX < 0f || posY < 0f) {
            resetPosition()
        } else {
            clampPosition()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val titleMetrics = titlePaint.fontMetrics
        val subtitleMetrics = subtitlePaint.fontMetrics
        val titleLineHeight = titleMetrics.descent - titleMetrics.ascent
        val subtitleLineHeight = subtitleMetrics.descent - subtitleMetrics.ascent
        // 每行在文字块内水平居中，视觉上以整块中心对齐。
        val titleX = posX + (blockWidth - titlePaint.measureText(title)) / 2f
        canvas.drawText(title, titleX, posY - titleMetrics.ascent, titlePaint)
        var baseline = posY + titleLineHeight + gap - subtitleMetrics.ascent
        subtitleLines.forEach { line ->
            val lineX = posX + (blockWidth - subtitlePaint.measureText(line)) / 2f
            canvas.drawText(line, lineX, baseline, subtitlePaint)
            baseline += subtitleLineHeight + lineGap
        }
    }

    private fun resetPosition() {
        val maxX = horizontalMax()
        val maxY = verticalMax()
        posX = ((width - blockWidth) / 2f).coerceIn(edgeInset, maxX)
        posY = ((height - blockHeight) / 2f).coerceIn(edgeInset, maxY)
        // 随机斜向：两个轴速度分量大小相同，碰到边缘就按该轴反向。
        velocityX = speedPxPerSecond * if (Random.nextBoolean()) 1f else -1f
        velocityY = speedPxPerSecond * if (Random.nextBoolean()) 1f else -1f
    }

    private fun clampPosition() {
        posX = posX.coerceIn(edgeInset, horizontalMax())
        posY = posY.coerceIn(edgeInset, verticalMax())
    }

    private fun advance(deltaSeconds: Float) {
        if (blockWidth <= 0f || blockHeight <= 0f) return
        val maxX = horizontalMax()
        val maxY = verticalMax()
        posX += velocityX * deltaSeconds
        posY += velocityY * deltaSeconds
        if (posX <= edgeInset) {
            posX = edgeInset
            velocityX = abs(velocityX)
        } else if (posX >= maxX) {
            posX = maxX
            velocityX = -abs(velocityX)
        }
        if (posY <= edgeInset) {
            posY = edgeInset
            velocityY = abs(velocityY)
        } else if (posY >= maxY) {
            posY = maxY
            velocityY = -abs(velocityY)
        }
    }

    private fun horizontalMax(): Float =
        (width - blockWidth - edgeInset).coerceAtLeast(edgeInset)

    private fun verticalMax(): Float =
        (height - blockHeight - edgeInset).coerceAtLeast(edgeInset)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
