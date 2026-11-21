package cn.helilab.autorunner.overlay

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 用一个不可见、不可触摸的系统悬浮窗把整块屏幕固定为横屏。
 *
 * 窗口参数带 `screenOrientation = SCREEN_ORIENTATION_LANDSCAPE` 的
 * `TYPE_APPLICATION_OVERLAY` 悬浮窗，在其可见期间会让系统按横屏旋转整块屏幕——
 * 这也是第三方「屏幕方向控制」类应用的标准做法。手柄标定开始后会退到桌面，而
 * 桌面通常锁竖屏、手柄游戏是横屏：标定层因此提供「转屏」开关，让用户能把桌面横
 * 过来再铺按键布局（见 [GamepadCalibrationService.toggleOrientation]）。
 *
 * 窗口自身完全透明且 `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`，不影响触摸与
 * 按键；移除窗口即恢复系统原有的方向行为。需要「显示在其他应用上层」权限——与
 * 标定悬浮层同一次授权，[AndroidGamepadCalibrationController.arm] 已先行校验。
 */
class OrientationLockWindow(private val context: Context) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var view: View? = null

    /** 已在强制横屏时为 `true`。 */
    val isForcing: Boolean get() = view != null

    /** 添加强制横屏窗口；已在强制中直接返回 `true`，窗口添加失败返回 `false`。 */
    fun forceLandscape(): Boolean {
        if (view != null) return true
        val manager = windowManager ?: return false
        return runCatching {
            val view = View(context)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 三个 NOT 标记保证窗口对触摸与按键完全透明，只贡献方向请求。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            manager.addView(view, params)
            this.view = view
            true
        }.getOrDefault(false)
    }

    /** 移除窗口，恢复系统方向行为；未添加时调用同样安全。 */
    fun release() {
        val current = view ?: return
        view = null
        runCatching { windowManager?.removeViewImmediate(current) }
    }
}
