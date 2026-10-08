package cn.helilab.autorunner.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.autorunner.core.platform.DockEdge
import com.autorunner.ui.theme.Dimens

/**
 * Bridges Jetpack Compose into a `WindowManager` overlay window.
 *
 * Compose requires the three tree owners (`LifecycleOwner`,
 * `ViewModelStoreOwner`, `SavedStateRegistryOwner`); a plain `Service` provides
 * none of them, so [OverlayLifecycleOwner] supplies a minimal implementation.
 *
 * 悬浮窗只有两种形态：仅悬浮球（[menuOpen] = false），以及「球 + 迷你菜单」
 * （[menuOpen] = true）。球的屏幕位置以绝对坐标 [ballX]/[ballY] 为真源，可拖动到
 * 任意位置（不做边缘吸附）。两种形态下球的屏幕位置保持一致——窗口高度不加限制，
 * 菜单向下展开，因此 y 不变；菜单态窗口 x 由 [ballX] 按内容宽度回推，使球的位置
 * 不随窗口放宽而跳动。`dockedEdge` 仅表示球位于左/右半屏，用于决定菜单展开方向。
 */
class ComposeWindowHost(
    private val context: Context,
    private val onDockEdgeChanged: (DockEdge) -> Unit = {},
) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var composeView: ComposeView? = null

    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private var layoutParams: WindowManager.LayoutParams? = null

    /** 最近一次测得的 Compose 内容尺寸（像素），用于收紧菜单态窗口并按球回推 x。 */
    private var contentSize: IntSize = IntSize.Zero

    /** 悬浮球左上角在屏幕上的绝对坐标（像素）；位置的真源，拖动时更新。 */
    private var ballX: Int = 0
    private var ballY: Int = 0

    /** 首次布局前 [ballX]/[ballY] 尚未初始化，置为默认位置。 */
    private var ballPositionInitialized = false

    /** `true` 时在球下方展开迷你快捷菜单。 */
    val menuOpen: MutableState<Boolean> = mutableStateOf(false)

    val isAttached: Boolean get() = composeView != null

    /** 球位于左/右半屏，仅用于决定菜单展开方向（不再吸附边缘）。 */
    var dockedEdge: MutableState<DockEdge> = mutableStateOf(DockEdge.RIGHT)
        private set

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
            layoutParams = params
            // `buildLayoutParams` can only guess the initial size; the menu
            // state decides the real one.
            applyLayoutParams()
            owner.onStart()
            true
        }.getOrDefault(false)
    }

    /** Replaces the rendered content (used when the settings change). */
    fun updateContent(content: @Composable () -> Unit) {
        composeView?.setContent { content() }
    }

    /** Switches between the ball and the ball + mini menu. */
    fun setMenuOpen(value: Boolean) {
        // No early return: the window size must match the content even when the
        // state value happens to be unchanged (the initial attach path relies on
        // it).
        menuOpen.value = value
        applyLayoutParams()
    }

    fun toggleMenu() = setMenuOpen(!menuOpen.value)

    /**
     * 由 Compose 内容在布局结束时回报自身尺寸。菜单态窗口用 `WRAP_CONTENT`
     * 贴合内容，x 需要按内容宽度回推，才能让球的位置不随窗口放宽而跳动。
     */
    fun setContentSize(size: IntSize) {
        if (size == contentSize) return
        contentSize = size
        applyLayoutParams()
    }

    /**
     * 屏幕方向变化时调用：横竖屏切换后屏幕宽度改变，需按新的屏幕尺寸重新夹取球坐标，
     * 否则会停留在切换前的绝对坐标而跑到屏幕外。
     */
    fun onConfigurationChanged() {
        applyLayoutParams()
    }

    /** Drag modifier handed to the ball. */
    fun dragModifier(): Modifier = Modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            val metrics = context.resources.displayMetrics
            val ballWindow = ballWindowSize()
            ballX = (ballX + dragAmount.x.toInt())
                .coerceIn(0, (metrics.widthPixels - ballWindow).coerceAtLeast(0))
            ballY = (ballY + dragAmount.y.toInt())
                .coerceIn(0, (metrics.heightPixels - ballWindow).coerceAtLeast(0))
            applyLayoutParams()
        }
    }

    /** Removes the window and releases the Compose owners. */
    fun remove() {
        val view = composeView ?: return
        composeView = null
        runCatching { windowManager?.removeViewImmediate(view) }
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        layoutParams = null
    }

    private fun applyLayoutParams() {
        val params = layoutParams ?: return
        val metrics = context.resources.displayMetrics
        val ballWindow = ballWindowSize()
        if (!ballPositionInitialized) {
            ballX = (metrics.widthPixels - ballWindow - dp(8)).coerceAtLeast(dp(8))
            ballY = dp(160)
            ballPositionInitialized = true
        }
        // 窗口尺寸变化（旋转）后按新屏幕夹取，避免球停在屏外。
        ballX = ballX.coerceIn(0, (metrics.widthPixels - ballWindow).coerceAtLeast(0))
        ballY = ballY.coerceIn(0, (metrics.heightPixels - ballWindow).coerceAtLeast(0))
        updateDockedEdge(metrics.widthPixels, ballWindow)
        if (menuOpen.value) {
            // 内容自适应：窗口贴合 Compose 内容，避免空白区域吞掉游戏触摸。
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            // 球贴向窗口的哪一侧由半屏位置决定；右半屏时窗口向左展开，
            // 需把球坐标换算回窗口左上角。
            val contentWidth = contentSize.width.takeIf { it > 0 } ?: ballWindow
            params.x = if (dockedEdge.value == DockEdge.RIGHT) {
                ballX - (contentWidth - ballWindow)
            } else {
                ballX
            }
        } else {
            params.width = ballWindow
            params.height = ballWindow
            params.x = ballX
        }
        // 球态与菜单态共用同一套 y，保证切换菜单时球的纵向位置不跳动。
        params.y = ballY
        runCatching { windowManager?.updateViewLayout(composeView, params) }
    }

    /** 按球的横向中心判定它落在左半屏还是右半屏；仅用于决定菜单展开方向。 */
    private fun updateDockedEdge(screenWidth: Int, ballWindow: Int) {
        val edge = if (ballX + ballWindow / 2 < screenWidth / 2) DockEdge.LEFT else DockEdge.RIGHT
        if (edge == dockedEdge.value) return
        dockedEdge.value = edge
        onDockEdgeChanged(edge)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val ballWindow = ballWindowSize()
        val metrics = context.resources.displayMetrics
        return WindowManager.LayoutParams(
            ballWindow,
            ballWindow,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_TOUCH_MODAL is mandatory here: a touchable overlay window
            // without it consumes *every* pointer event on the display, inside or
            // outside its bounds, which makes the whole device untappable while
            // the floating ball/panel is visible.
            //
            // FLAG_NOT_FOCUSABLE is kept in *both* states: the overlay no longer
            // hosts any text field, so it must never steal focus (or the IME)
            // from the game below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // 窗口在整块屏幕内布局，x/y 按整屏绝对坐标解释。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - ballWindow - dp(8)
            y = dp(160)
        }
    }

    private fun ballWindowSize(): Int =
        dp((Dimens.FloatingBallSize + Dimens.FloatingBallWindowPadding).value.toInt())

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/**
 * Minimal `LifecycleOwner` / `ViewModelStoreOwner` / `SavedStateRegistryOwner`
 * trio required to host `ComposeView` outside an Activity.
 */
internal class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    private val store = ViewModelStore()

    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore get() = store

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
