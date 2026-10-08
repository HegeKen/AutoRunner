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
 * `WindowManager` host for the gamepad calibration layer.
 *
 * The calibration layer is a single permanent, fully transparent full screen
 * window: it lays every gamepad button over the real game so the user can drag
 * them into place. The floating ball and its long-press menu live *inside* the
 * Compose content (see [com.autorunner.ui.overlay.GamepadCalibrationPanel]),
 * so this host never has to resize or move the window itself.
 *
 * The window keeps `FLAG_LAYOUT_IN_SCREEN` and lays out inside the display
 * cutout, so the Compose content origin is the physical screen origin `(0, 0)`;
 * the absolute pixel coordinates produced by the calibration panel therefore
 * match the coordinate system used by the recorded scripts and the stored
 * gamepad mappings. Without the cutout mode the window is letterboxed on a
 * landscape punch-hole display and the origin shifts by the cutout inset
 * (e.g. 144px / 48dp), making every stored coordinate miss the real button.
 */
class GamepadCalibrationWindowHost(
    private val context: Context,
) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var composeView: ComposeView? = null

    private var lifecycleOwner: OverlayLifecycleOwner? = null

    val isAttached: Boolean get() = composeView != null

    /** Attaches the window rendering [content]; returns `false` on failure. */
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

    /** Replaces the rendered content. */
    fun updateContent(content: @Composable () -> Unit) {
        composeView?.setContent { content() }
    }

    /** Removes the window and releases the Compose owners. */
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
            // FLAG_NOT_TOUCH_MODAL is mandatory: a touchable overlay window
            // without it consumes *every* pointer event on the display.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Lay out inside the cutout so the content origin stays at the
            // physical (0, 0) even on a landscape punch-hole display. Otherwise
            // the window is letterboxed by the cutout inset and every calibrated
            // absolute coordinate shifts by that inset.
            // ALWAYS (API 30+) is used when available; SHORT_EDGES is the
            // equivalent fallback for API 28/29 (punch-holes sit on a short edge).
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
}
