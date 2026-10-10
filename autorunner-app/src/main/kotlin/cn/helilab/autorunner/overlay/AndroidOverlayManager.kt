package cn.helilab.autorunner.overlay

import android.content.Context
import android.provider.Settings
import com.autorunner.core.platform.OverlayManager
import com.autorunner.core.platform.OverlayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级的悬浮层状态。
 *
 * 悬浮窗存活于 `AutoRunnerOverlayService` 中，但状态需要能被 Activity 观察；
 * 用一个共享持有者让两者保持同步，无需绑定到该服务。
 */
object OverlayStateHolder {

    private val _state = MutableStateFlow(OverlayState())

    val state: StateFlow<OverlayState> = _state.asStateFlow()

    fun update(transform: (OverlayState) -> OverlayState) {
        _state.value = transform(_state.value)
    }
}

/**
 * 供悬浮层服务使用的一次性待办动作。
 *
 * App 内的 ViewModel 与 [AutoRunnerOverlayService] 持有**各自独立**的
 * ViewModel 实例，因此“运行脚本 X” / “开始录制”请求无法通过 ViewModel 状态
 * 传递。一个进程级持有者来弥合这一空隙：服务观察它、武装自己的 ViewModel、
 * 收起菜单回到悬浮球，然后清除该请求。
 */
object PendingOverlayAction {

    enum class Mode { RUN, RECORD }

    data class Pending(val mode: Mode, val scriptId: String? = null)

    private val _pending = MutableStateFlow<Pending?>(null)

    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun request(mode: Mode, scriptId: String? = null) {
        _pending.value = Pending(mode, scriptId)
    }

    fun consume() {
        _pending.value = null
    }
}

/**
 * `OverlayManager` 实现（§6.4.2）。
 *
 * `SYSTEM_ALERT_WINDOW` 是特殊权限，无法通过运行时 API 请求，因此
 * [isPermissionGranted] 检查 `Settings.canDrawOverlays(context)`，并由 UI 负责
 * 把用户引导至 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`。
 */
class AndroidOverlayManager(
    private val context: Context,
) : OverlayManager {

    override val state: StateFlow<OverlayState> = OverlayStateHolder.state

    override fun isPermissionGranted(): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    override fun goHome() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }


    override fun show(): Boolean {
        if (!isPermissionGranted()) {
            OverlayStateHolder.update {
                it.copy(
                    permissionGranted = false,
                    visible = false,
                    error = "未授予悬浮窗权限，无法显示控制面板",
                )
            }
            return false
        }
        OverlayStateHolder.update { it.copy(permissionGranted = true, error = null) }
        AutoRunnerOverlayService.start(context)
        // 服务会异步确认附着结果。
        OverlayStateHolder.update { it.copy(visible = true) }
        return true
    }

    override fun armExecution(scriptId: String): Boolean {
        if (!isPermissionGranted()) {
            OverlayStateHolder.update {
                it.copy(
                    permissionGranted = false,
                    visible = false,
                    error = "未授予悬浮窗权限，无法显示控制面板",
                )
            }
            return false
        }
        OverlayStateHolder.update {
            it.copy(permissionGranted = true, error = null, menuOpen = false)
        }
        PendingOverlayAction.request(PendingOverlayAction.Mode.RUN, scriptId)
        AutoRunnerOverlayService.start(context)
        // 服务会异步确认附着结果。
        OverlayStateHolder.update { it.copy(visible = true) }
        return true
    }

    override fun hide() {
        AutoRunnerOverlayService.stop(context)
        OverlayStateHolder.update { it.copy(visible = false) }
    }

    override fun toggle() {
        if (state.value.visible) hide() else show()
    }

    override fun expand() {
        if (!state.value.visible) {
            show()
            return
        }
        AutoRunnerOverlayService.sendCommand(context, AutoRunnerOverlayService.ACTION_EXPAND)
        OverlayStateHolder.update { it.copy(menuOpen = true) }
    }

    override fun collapse() {
        if (!state.value.visible) return
        AutoRunnerOverlayService.sendCommand(context, AutoRunnerOverlayService.ACTION_COLLAPSE)
        OverlayStateHolder.update { it.copy(menuOpen = false) }
    }

    override fun refreshPermissionState() {
        val granted = isPermissionGranted()
        OverlayStateHolder.update {
            it.copy(
                permissionGranted = granted,
                error = if (granted) null else it.error,
                visible = it.visible && granted,
            )
        }
    }
}
