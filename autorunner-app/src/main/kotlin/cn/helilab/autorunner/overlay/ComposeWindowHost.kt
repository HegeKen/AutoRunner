package cn.helilab.autorunner.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
 * 把 Jetpack Compose 接入 `WindowManager` 悬浮窗的桥梁。
 *
 * Compose 需要三个树属主（`LifecycleOwner`、`ViewModelStoreOwner`、
 * `SavedStateRegistryOwner`）；普通的 `Service` 一个都提供不了，因此
 * [OverlayLifecycleOwner] 提供了一套最小实现。
 *
 * 悬浮窗只有两种形态：仅悬浮球（[menuOpen] = false），以及「球 + 迷你菜单」
 * （[menuOpen] = true）。球的屏幕位置以绝对坐标 [ballX]/[ballY] 为真源，可拖动到
 * 任意位置（不做边缘吸附）。两种形态下球的屏幕位置保持一致：菜单横向按
 * [dockedEdge] 决定朝左/右展开，纵向按球所在的上下半屏决定向下/向上展开
 * （[menuAbove]），窗口坐标再用 [computeOverlayWindowPlacement] 回推并夹取到屏幕内，
 * 避免球贴近屏幕底部时长按菜单被裁掉。`dockedEdge` 仅表示球位于左/右半屏，用于决定
 * 菜单展开方向。
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

    /**
     * 上一次菜单展开时测得的内容尺寸。
     *
     * 打开菜单的瞬间 [contentSize] 仍是「仅球」的旧尺寸，若直接拿它回推窗口 y，
     * 菜单会先出现在错误位置再跳一下。保留上一次的结果作为首帧估算，等真实尺寸
     * 回报后 [menuContentMeasured] 置位，即改用新的测量值。
     */
    private var lastMenuSize: IntSize = IntSize.Zero

    /** [contentSize] 是否已经是菜单展开态的测量结果。 */
    private var menuContentMeasured = false

    /** 悬浮球左上角在屏幕上的绝对坐标（像素）；位置的真源，拖动时更新。 */
    private var ballX: Int = 0
    private var ballY: Int = 0

    /** 首次布局前 [ballX]/[ballY] 尚未初始化，置为默认位置。 */
    private var ballPositionInitialized = false

    /** `true` 时展开迷你快捷菜单。 */
    val menuOpen: MutableState<Boolean> = mutableStateOf(false)

    /**
     * 菜单在球的上方展开（球位于屏幕下半部分）。
     *
     * 由 [applyLayoutParams] 依据球心位置维护，Compose 内容据此调整球的上下顺序，
     * 使长按菜单始终朝屏幕空间更大的一侧展开。
     */
    val menuAbove: MutableState<Boolean> = mutableStateOf(false)

    val isAttached: Boolean get() = composeView != null

    /** 球位于左/右半屏，仅用于决定菜单的横向展开方向（不再吸附边缘）。 */
    var dockedEdge: MutableState<DockEdge> = mutableStateOf(DockEdge.RIGHT)
        private set

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
            layoutParams = params
            // `buildLayoutParams` 只能猜测初始尺寸；真正的尺寸由菜单
            // 状态决定。
            applyLayoutParams()
            owner.onStart()
            true
        }.getOrDefault(false)
    }

    /** 替换已渲染的内容（设置变更时使用）。 */
    fun updateContent(content: @Composable () -> Unit) {
        composeView?.setContent { content() }
    }

    /** 在“仅球”与“球 + 迷你菜单”两种形态之间切换。 */
    fun setMenuOpen(value: Boolean) {
        // 不做提前返回：即使状态值恰好未变，窗口尺寸也必须与内容保持一致
        // （初次附着的路径依赖这一点）。
        menuOpen.value = value
        // 收起菜单后内容会重新测回“仅球”尺寸；下次展开时先用缓存值估算位置。
        if (!value) menuContentMeasured = false
        applyLayoutParams()
    }

    fun toggleMenu() = setMenuOpen(!menuOpen.value)

    /**
     * 由 Compose 内容在布局结束时回报自身尺寸。菜单态窗口用 `WRAP_CONTENT`
     * 贴合内容，x / y 需要按内容尺寸回推，才能让球的位置不随窗口放宽、菜单展开
     * 方向变化而跳动。
     */
    fun setContentSize(size: IntSize) {
        if (size == contentSize) {
            // 尺寸未变：若这是切换菜单态后的首次测量（例如两种状态的尺寸恰好相同），
            // 仍要标记为已测量，后续布局才不会继续用旧缓存。
            if (menuOpen.value) menuContentMeasured = true
            return
        }
        contentSize = size
        if (menuOpen.value) {
            lastMenuSize = size
            menuContentMeasured = true
        }
        applyLayoutParams()
    }

    /**
     * 屏幕方向变化时调用：横竖屏切换后屏幕宽度改变，需按新的屏幕尺寸重新夹取球坐标，
     * 否则会停留在切换前的绝对坐标而跑到屏幕外。
     */
    fun onConfigurationChanged() {
        applyLayoutParams()
    }

    /** 交给悬浮球的拖动 modifier。 */
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

    /**
     * 切换悬浮球窗口是否接收触摸。
     *
     * 黑屏挂机层由无障碍服务添加（`TYPE_ACCESSIBILITY_OVERLAY`）时位于本窗口
     * 之上，球被完全遮住却仍可触摸：脚本注入的点击落在球矩形内会被本窗口吞掉，
     * 无法下传到游戏。黑屏期间置为不可触摸，退出黑屏后恢复。
     */
    fun setTouchable(touchable: Boolean) {
        val view = composeView ?: return
        val params = layoutParams ?: return
        val flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags == params.flags) return
        params.flags = flags
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    /** 移除窗口并释放 Compose 属主。 */
    fun remove() {
        val view = composeView ?: return
        composeView = null
        runCatching { windowManager?.removeViewImmediate(view) }
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        layoutParams = null
    }

    /**
     * 把悬浮窗重新移到窗口栈顶。
     *
     * 黑屏挂机层是后加入的全屏覆盖窗口，会盖住悬浮球；重新添加一次悬浮窗即可
     * 回到最上层，保证用户仍能长按悬浮球退出黑屏。延迟到下一次主线程循环执行，
     * 避免在正在处理该窗口点击事件时移除自身窗口。
     */
    fun bringToFront() {
        val view = composeView ?: return
        view.post {
            if (composeView !== view) return@post
            val params = layoutParams ?: return@post
            runCatching {
                windowManager?.removeViewImmediate(view)
                windowManager?.addView(view, params)
            }
        }
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

        val open = menuOpen.value
        // 菜单展开的瞬间 Compose 还没重新测量；用上一次菜单态尺寸做首帧估算，
        // 避免球先跳到错误位置再回弹。
        val measuredSize = if (menuContentMeasured) contentSize else lastMenuSize
        val contentWidth = if (open) {
            measuredSize.width.takeIf { it > 0 } ?: ballWindow
        } else {
            ballWindow
        }
        val contentHeight = if (open) {
            measuredSize.height.takeIf { it > 0 } ?: ballWindow
        } else {
            ballWindow
        }

        val placement = computeOverlayWindowPlacement(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            ballWindow = ballWindow,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            ballX = ballX,
            ballY = ballY,
            menuOpen = open,
            dockedEdge = dockedEdge.value,
        )
        // Compose 内容据此决定菜单画在球上方还是下方。
        menuAbove.value = placement.menuAbove

        if (open) {
            // 内容自适应：窗口贴合 Compose 内容，避免空白区域吞掉游戏触摸。
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            params.width = ballWindow
            params.height = ballWindow
        }
        params.x = placement.x
        params.y = placement.y
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
            // FLAG_NOT_TOUCH_MODAL 在此是必需的：可触摸的悬浮窗若缺少它，
            // 会吞噬显示上的每一个指针事件——无论在其边界之内还是之外——
            // 只要悬浮球/面板可见，整台设备就无法点击。
            //
            // FLAG_NOT_FOCUSABLE 在两种形态下都保留：悬浮层不再承载任何
            // 输入框，因此绝不能从下层游戏抢走焦点（或输入法）。
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
            // 与标定层同理：窗口需铺满含 cutout 的物理屏，否则横屏打孔屏下会被
            // letterbox，球的绝对坐标随之整体偏移一个 cutout inset。
            // ALWAYS（API 30+）优先，API 28/29 回退到 SHORT_EDGES（打孔屏在短边，等效）。
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun ballWindowSize(): Int =
        dp((Dimens.FloatingBallSize + Dimens.FloatingBallWindowPadding).value.toInt())

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/**
 * 在 Activity 之外承载 `ComposeView` 所需的最小
 * `LifecycleOwner` / `ViewModelStoreOwner` / `SavedStateRegistryOwner` 三件套。
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
