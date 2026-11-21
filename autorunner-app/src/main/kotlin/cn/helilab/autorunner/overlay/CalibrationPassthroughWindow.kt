package cn.helilab.autorunner.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 标定层「暂时穿透」期间常驻的恢复入口。
 *
 * 穿透开启后整个标定窗口 `FLAG_NOT_TOUCHABLE`，悬浮球与按键芯片都无法点按；
 * 本类用一个独立的小型悬浮窗提供唯一可触摸的「恢复标定操作」胶囊，点按后由
 * 宿主（[GamepadCalibrationService]）恢复标定层触摸并撤掉自己。窗口极小
 * （`WRAP_CONTENT`）且 `FLAG_NOT_FOCUSABLE`，不影响用户对真实页面的操作。
 */
class CalibrationPassthroughWindow(
    private val context: Context,
    private val onRestore: () -> Unit,
) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var view: TextView? = null

    val isShowing: Boolean get() = view != null

    /** 显示恢复胶囊；已在显示时返回 `true`，窗口添加失败返回 `false`。 */
    fun show(): Boolean {
        if (view != null) return true
        val manager = windowManager ?: return false
        return runCatching {
            val pill = TextView(context).apply {
                text = "恢复标定操作"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(18f)
                    setColor(0xDD262626.toInt())
                    setStroke(dp(1f).toInt(), 0x66FFFFFF)
                }
                setPadding(dp(16f).toInt(), dp(9f).toInt(), dp(16f).toInt(), dp(9f).toInt())
                elevation = dp(6f)
                setOnClickListener { onRestore() }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(48f).toInt()
            }
            manager.addView(pill, params)
            view = pill
            true
        }.getOrDefault(false)
    }

    /** 移除胶囊；未显示时调用同样安全。 */
    fun remove() {
        val current = view ?: return
        view = null
        runCatching { windowManager?.removeViewImmediate(current) }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
}
