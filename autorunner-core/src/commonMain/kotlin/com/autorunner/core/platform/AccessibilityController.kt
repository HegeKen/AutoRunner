package com.autorunner.core.platform

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ScreenMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 分发单个动作的结果。 */
sealed interface ActionResult {

    /** 手势已交给系统。 */
    data object Success : ActionResult

    /** 手势无法分发。 */
    data class Failure(val reason: String, val recoverable: Boolean = false) : ActionResult

    /** 该平台／配置不支持此动作类型。 */
    data class Unsupported(val reason: String) : ActionResult

    val isSuccess: Boolean get() = this is Success
}

/**
 * `AccessibilityService` 之上的平台抽象：它分发手势，并上报执行器
 * 用于换算归一化坐标的屏幕几何信息。
 *
 * Android 实现位于 `autorunner-app`，通过 `AndroidPlatformRegistry` 发布；
 * 其余平台退化为 [UnavailableAccessibilityController]。
 */
interface AccessibilityController {

    /** 无障碍服务已绑定时为 `true`。 */
    val isConnected: Boolean

    /** `dispatchGesture` 可用时为 `true`（API 24+，在我们的 minSdk 上恒为 true）。 */
    val supportsGestures: Boolean

    /** 手势分发所针对的屏幕。 */
    val screenMetrics: ScreenMetrics

    /** 从平台重新读取 [screenMetrics]（旋转、尺寸变化、折叠）。 */
    fun refreshScreenMetrics(): ScreenMetrics

    /** 分发一个动作并挂起直到手势完成。 */
    suspend fun perform(step: ActionStep): ActionResult

    /** 取消当前所有进行中的手势。 */
    fun cancelPendingGestures()
}

/** 未注册任何平台实现时使用的兜底实现。 */
object UnavailableAccessibilityController : AccessibilityController {
    override val isConnected: Boolean = false
    override val supportsGestures: Boolean = false
    override val screenMetrics: ScreenMetrics = ScreenMetrics.Unknown
    override fun refreshScreenMetrics(): ScreenMetrics = ScreenMetrics.Unknown
    override suspend fun perform(step: ActionStep): ActionResult =
        ActionResult.Failure("无障碍服务未连接", recoverable = true)

    override fun cancelPendingGestures() = Unit
}

/** 悬浮控制面板的可观察状态。 */
data class OverlayState(
    /** 已授予 `SYSTEM_ALERT_WINDOW`。 */
    val permissionGranted: Boolean = false,
    /** 悬浮层已附加。 */
    val visible: Boolean = false,
    /** 悬浮球长按的迷你动作菜单已展开。 */
    val menuOpen: Boolean = false,
    /** 悬浮球当前所在的屏幕半边；决定菜单展开方向。 */
    val dockedEdge: DockEdge = DockEdge.RIGHT,
    /** 窗口管理器上报的最近一次错误（若有）。 */
    val error: String? = null,
)

/** 悬浮球停靠的屏幕半边（不做贴边吸附）。 */
enum class DockEdge { LEFT, RIGHT }

/**
 * 控制悬浮面板的生命周期
 * （Android 上即 `AutoRunnerOverlayService`）。
 */
interface OverlayManager {
    /** 回到桌面：让用户先去打开需要操作的目标页面。 */
    fun goHome() {}


    val state: StateFlow<OverlayState>

    /** 当前是否已授予 `SYSTEM_ALERT_WINDOW`。 */
    fun isPermissionGranted(): Boolean

    /** 附加悬浮层；权限缺失时返回 `false`。 */
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

    /** 用户从系统设置页返回后刷新 [state]。 */
    fun refreshPermissionState()
}

/** 没有窗口管理器悬浮层的平台所用的兜底实现。 */
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
