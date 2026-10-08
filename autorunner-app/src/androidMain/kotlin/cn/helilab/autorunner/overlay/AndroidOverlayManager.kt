package cn.helilab.autorunner.overlay

import android.content.Context
import android.provider.Settings
import com.autorunner.core.platform.OverlayManager
import com.autorunner.core.platform.OverlayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process wide overlay state.
 *
 * The overlay window lives in `AutoRunnerOverlayService`, but the state has to
 * be observable from the activity; a single shared holder keeps both in sync
 * without binding to the service.
 */
object OverlayStateHolder {

    private val _state = MutableStateFlow(OverlayState())

    val state: StateFlow<OverlayState> = _state.asStateFlow()

    fun update(transform: (OverlayState) -> OverlayState) {
        _state.value = transform(_state.value)
    }
}

/**
 * Pending one-shot action for the overlay service.
 *
 * The in-app ViewModels and [AutoRunnerOverlayService] own **separate**
 * ViewModel instances, so "run script X" / "start recording" requests cannot be
 * passed through the ViewModel state. A process-wide holder bridges the gap:
 * the service observes it, arms its own ViewModels, collapses to the ball and
 * clears the request.
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
 * `OverlayManager` implementation (§6.4.2).
 *
 * `SYSTEM_ALERT_WINDOW` is a special permission that cannot be requested with
 * the runtime API, so [isPermissionGranted] checks
 * `Settings.canDrawOverlays(context)` and the UI is responsible for sending the
 * user to `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`.
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
        // The service confirms attachment asynchronously.
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
        // The service confirms attachment asynchronously.
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
