package cn.helilab.autorunner.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import cn.helilab.autorunner.notification.IdleNotification
import kotlin.math.max
import kotlin.math.min

/**
 * 黑屏挂机层上的通知看板。
 *
 * 叠在 [DvdHintView] 上方，透明背景，只在收到通知时绘制。通知只能看不能点：
 * 整个黑屏窗口是 `FLAG_NOT_TOUCHABLE` 的，触摸必须继续穿透给下层游戏。
 *
 * 展示规则：
 * * 最多同时显示 [MAX_CARDS] 条，新的在上；超过后最旧的被挤出；
 * * 每条 [DISPLAY_MS] 后自动消失，避免长期占用屏幕（烧屏）与隐私暴露；
 * * 正文最多两行，超长截断。
 *
 * 视图只在增删通知时重绘，不参与 [DvdHintView] 的逐帧动画，挂机时的额外
 * 绘制开销可以忽略。
 */
class IdleNotificationView(context: Context) : View(context) {

    private data class Card(val notification: IdleNotification, val expiresAt: Long)

    private val cards = ArrayDeque<Card>()

    private val handler = Handler(Looper.getMainLooper())

    private val expiry = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val removed = cards.removeAll { it.expiresAt <= now }
            if (removed) invalidate()
            scheduleExpiry()
        }
    }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(236, 18, 18, 18)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 205, 205, 205)
        textSize = sp(11f)
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 245, 245, 245)
        textSize = sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 215, 215, 215)
        textSize = sp(13f)
    }

    /** 加入一条通知；没有附着到窗口时直接忽略（此时也不该有黑屏层可见）。 */
    fun add(notification: IdleNotification) {
        if (!isAttachedToWindow) return
        cards.addFirst(Card(notification, notification.postedAtMs + DISPLAY_MS))
        while (cards.size > MAX_CARDS) cards.removeLast()
        scheduleExpiry()
        invalidate()
    }

    fun clear() {
        handler.removeCallbacks(expiry)
        if (cards.isNotEmpty()) {
            cards.clear()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleExpiry()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(expiry)
        cards.clear()
        super.onDetachedFromWindow()
    }

    private fun scheduleExpiry() {
        handler.removeCallbacks(expiry)
        val next = cards.minOfOrNull { it.expiresAt } ?: return
        handler.postDelayed(expiry, (next - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cards.isEmpty()) return

        val edge = dp(16f)
        val cardWidth = min(width - edge * 2, dp(420f))
        if (cardWidth <= 0f) return
        val left = (width - cardWidth) / 2f
        var top = dp(56f)
        // 底部留出空间，避免通知完全盖住底部区域；放不下的卡片直接不画。
        val bottomLimit = height - dp(96f)

        for (card in cards) {
            if (top >= bottomLimit) break
            val height = drawCard(canvas, card.notification, left, top, cardWidth)
            top += height + dp(8f)
        }
    }

    /** 绘制一张通知卡片并返回其高度。 */
    private fun drawCard(
        canvas: Canvas,
        notification: IdleNotification,
        x: Float,
        y: Float,
        width: Float,
    ): Float {
        val padding = dp(12f)
        val iconSize = dp(22f)
        val iconGap = dp(10f)
        val hasIcon = notification.icon != null
        val textLeft = x + padding + if (hasIcon) iconSize + iconGap else 0f
        val textWidth = (x + width - padding - textLeft).coerceAtLeast(dp(40f))

        val labelLines = wrapText(notification.appLabel, labelPaint, textWidth, 1)
        val titleLines = wrapText(notification.title, titlePaint, textWidth, 1)
        val bodyLines = wrapText(notification.text, bodyPaint, textWidth, 2)
        val labelHeight = lineHeight(labelPaint)
        val titleHeight = lineHeight(titlePaint)
        val bodyHeight = lineHeight(bodyPaint)
        val textHeight = labelLines.size * labelHeight +
            titleLines.size * titleHeight +
            bodyLines.size * bodyHeight
        val height = max(textHeight + padding * 2, iconSize + padding * 2)

        canvas.drawRoundRect(
            x,
            y,
            x + width,
            y + height,
            dp(14f),
            dp(14f),
            cardPaint,
        )
        canvas.drawRoundRect(
            x,
            y,
            x + width,
            y + height,
            dp(14f),
            dp(14f),
            borderPaint,
        )

        notification.icon?.let { icon ->
            val iconTop = y + padding
            icon.setBounds(
                (x + padding).toInt(),
                iconTop.toInt(),
                (x + padding + iconSize).toInt(),
                (iconTop + iconSize).toInt(),
            )
            icon.draw(canvas)
        }

        var baseline = y + padding - labelPaint.fontMetrics.ascent
        labelLines.forEach { line ->
            canvas.drawText(line, textLeft, baseline, labelPaint)
            baseline += labelHeight
        }
        titleLines.forEach { line ->
            canvas.drawText(line, textLeft, baseline, titlePaint)
            baseline += titleHeight
        }
        bodyLines.forEach { line ->
            canvas.drawText(line, textLeft, baseline, bodyPaint)
            baseline += bodyHeight
        }
        return height
    }

    private fun lineHeight(paint: Paint): Float =
        paint.fontMetrics.descent - paint.fontMetrics.ascent + dp(1f)

    /**
     * 按像素宽度把 [text] 折成最多 [maxLines] 行；超出部分以省略号结尾。
     *
     * 中文没有空格，按字符断行；英文优先在空格处断开，避免把单词切成两半。
     */
    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float,
        maxLines: Int,
    ): List<String> {
        if (text.isEmpty() || maxWidth <= 0f || maxLines <= 0) return emptyList()
        var remaining = text.replace('\n', ' ').trim()
        val lines = ArrayList<String>(maxLines)
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            val count = paint.breakText(remaining, true, maxWidth, null)
            if (count <= 0) break
            if (count >= remaining.length) {
                lines += remaining
                remaining = ""
                break
            }
            var cut = count
            val space = remaining.lastIndexOf(' ', cut)
            if (space > cut * 0.6f) cut = space
            lines += remaining.substring(0, cut).trimEnd()
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty() && lines.isNotEmpty()) {
            lines[lines.size - 1] = ellipsize(lines.last() + remaining, paint, maxWidth)
        }
        return lines
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        val ellipsis = "…"
        if (paint.measureText(text + ellipsis) <= maxWidth) return text + ellipsis
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText(ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        /** 同时显示的最大条数。 */
        const val MAX_CARDS = 3

        /** 每条通知的展示时长。 */
        const val DISPLAY_MS = 8_000L
    }
}
