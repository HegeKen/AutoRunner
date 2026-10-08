package cn.helilab.autorunner.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 手柄校准层的 `WindowManager` 宿主。
 *
 * 校准层是一个常驻、完全透明的全屏窗口：它把每个手柄按键铺在真实游戏画面之上，
 * 供用户拖动就位。悬浮球及其长按菜单位于 Compose 内容*内部*（见
 * [com.autorunner.ui.overlay.GamepadCalibrationPanel]），因此本宿主无需自行
 * 调整窗口大小或位置。
 *
 * 窗口保留 `FLAG_LAYOUT_IN_SCREEN` 并在显示 cutout 区域内布局，因此 Compose
 * 内容原点即物理屏幕原点 `(0, 0)`；校准面板产出的绝对像素坐标也因此与录制
 * 脚本、已存手柄映射所用的坐标系一致。缺少该 cutout 模式时，窗口在横屏打孔屏
 * 上会被 letterbox，原点随之偏移一个 cutout inset（例如 144px / 48dp），
 * 导致每个存储的坐标都打不中真实按键。
 */
class GamepadCalibrationWindowHost(
    private val context: Context,
) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var composeView: ComposeView? = null

    private var lifecycleOwner: OverlayLifecycleOwner? = null

    val isAttached: Boolean get() = composeView != null

    /** 附着渲染 [content] 的窗口；失败时返回 `false`。 */
    fun show(content: @Composable () -> Unit): Boolean {
        val manager = windowManager ?: return false
        if (composeView != null) {
            updateContent(content)
            return true
        }
        return runCatching {
            val owner = OverlayLifecycleOwner().apply { onCreate() }
            val params = buildLayoutParams()
            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent { content() }
            }
            manager.addView(view, params)
            lifecycleOwner = owner
            composeView = view
            owner.onStart()
            true
        }.getOrDefault(false)
    }

    /** 替换已渲染的内容。 */
    fun updateContent(content: @Composable () -> Unit) {
        composeView?.setContent { content() }
    }

    /** 移除窗口并释放 Compose 属主。 */
    fun remove() {
        val view = composeView ?: return
        composeView = null
        runCatching { windowManager?.removeViewImmediate(view) }
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_TOUCH_MODAL 是必需的：可触摸的悬浮窗若缺少它，
            // 会吞噬显示上的每一个指针事件。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 在 cutout 区域内布局，使内容原点在横屏打孔屏上仍停在物理 (0, 0)。
            // 否则窗口会被 cutout inset letterbox，每个校准出的绝对坐标都会
            // 整体偏移该 inset。
            // 可用时优先 ALWAYS（API 30+）；SHORT_EDGES 是 API 28/29 的等效
            // 回退（打孔位于短边）。
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
}
