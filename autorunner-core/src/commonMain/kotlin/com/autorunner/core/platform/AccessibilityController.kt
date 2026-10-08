package com.autorunner.core.platform

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ScreenMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Result of dispatching a single action. */
sealed interface ActionResult {

    /** The gesture was handed to the system. */
    data object Success : ActionResult

    /** The gesture could not be dispatched. */
    data class Failure(val reason: String, val recoverable: Boolean = false) : ActionResult

    /** The action type is not supported on this platform / configuration. */
    data class Unsupported(val reason: String) : ActionResult

    val isSuccess: Boolean get() = this is Success
}

/**
 * Platform abstraction over `AccessibilityService`: it dispatches gestures and
 * reports the screen geometry the executor scales normalised coordinates
 * against.
 *
 * Android implementation lives in `autorunner-app` and is published through
 * `AndroidPlatformRegistry`; the remaining platforms fall back to
 * [UnavailableAccessibilityController].
 */
interface AccessibilityController {

    /** `true` while the accessibility service is bound. */
    val isConnected: Boolean

    /** `true` when `dispatchGesture` is available (API 24+, always true on our minSdk). */
    val supportsGestures: Boolean

    /** Screen the gestures are dispatched against. */
    val screenMetrics: ScreenMetrics

    /** Re-reads [screenMetrics] from the platform (rotation, resize, fold). */
    fun refreshScreenMetrics(): ScreenMetrics

    /** Dispatches one action and suspends until the gesture completed. */
    suspend fun perform(step: ActionStep): ActionResult

    /** Cancels any gesture currently in flight. */
    fun cancelPendingGestures()
}

/** Fallback used when no platform implementation is registered. */
object UnavailableAccessibilityController : AccessibilityController {
    override val isConnected: Boolean = false
    override val supportsGestures: Boolean = false
    override val screenMetrics: ScreenMetrics = ScreenMetrics.Unknown
    override fun refreshScreenMetrics(): ScreenMetrics = ScreenMetrics.Unknown
    override suspend fun perform(step: ActionStep): ActionResult =
        ActionResult.Failure("无障碍服务未连接", recoverable = true)

    override fun cancelPendingGestures() = Unit
}

/** Observable state of the floating control panel. */
data class OverlayState(
    /** `SYSTEM_ALERT_WINDOW` granted. */
    val permissionGranted: Boolean = false,
    /** The overlay is attached. */
    val visible: Boolean = false,
    /** The ball's long-press mini action menu is expanded. */
    val menuOpen: Boolean = false,
    /** Half of the screen the ball currently sits on; decides the menu direction. */
    val dockedEdge: DockEdge = DockEdge.RIGHT,
    /** Last error surfaced by the window manager, if any. */
    val error: String? = null,
)

/** Half of the screen the floating ball sits on (no edge snapping). */
enum class DockEdge { LEFT, RIGHT }

/**
 * Controls the lifecycle of the floating panel
 * (`AutoRunnerOverlayService` on Android).
 */
interface OverlayManager {
    /** 回到桌面：让用户先去打开需要操作的目标页面。 */
    fun goHome() {}


    val state: StateFlow<OverlayState>

    /** `SYSTEM_ALERT_WINDOW` currently granted. */
    fun isPermissionGranted(): Boolean

    /** Attaches the overlay; returns `false` when the permission is missing. */
    fun show(): Boolean

    /**
     * 脚本列表点「运行」：以悬浮球形式显示，并记住待运行的脚本 ID，
     * 用户单击悬浮球即开始执行（无需展开面板）。平台不支持时退化为普通 [show]。
     */
    fun armExecution(scriptId: String): Boolean = show()

    fun hide()

    fun toggle()

    fun expand()

    fun collapse()

    /** Refreshes [state] after the user returns from the system settings page. */
    fun refreshPermissionState()
}

/** Fallback overlay manager for platforms without a window manager overlay. */
class UnavailableOverlayManager(
    private val permissionGranted: Boolean = false,
) : OverlayManager {

    private val _state = MutableStateFlow(OverlayState(permissionGranted = permissionGranted))

    override val state: StateFlow<OverlayState> = _state.asStateFlow()

    override fun isPermissionGranted(): Boolean = _state.value.permissionGranted

    override fun show(): Boolean = false

    override fun hide() = Unit

    override fun toggle() = Unit

    override fun expand() = Unit

    override fun collapse() = Unit

    override fun refreshPermissionState() = Unit
}
