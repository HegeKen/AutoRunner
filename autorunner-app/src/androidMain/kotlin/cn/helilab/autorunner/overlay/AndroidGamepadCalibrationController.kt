package cn.helilab.autorunner.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.autorunner.core.platform.GamepadCalibrationController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process wide calibration state.
 *
 * The calibration window lives in [GamepadCalibrationService] while the state is
 * observed from the activity / shared UI; a single shared holder keeps both in
 * sync without binding to the service.
 */
object GamepadCalibrationStateHolder {

    private val _active = MutableStateFlow(false)

    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(value: Boolean) {
        _active.value = value
    }
}

/**
 * `GamepadCalibrationController` implementation backed by
 * [GamepadCalibrationService].
 *
 * Arming requires the `SYSTEM_ALERT_WINDOW` permission; when it is missing
 * [arm] returns `false` and the caller is expected to explain how to grant it.
 */
class AndroidGamepadCalibrationController(
    private val context: Context,
) : GamepadCalibrationController {

    override val active: StateFlow<Boolean> = GamepadCalibrationStateHolder.active

    override fun arm(): Boolean {
        if (!isPermissionGranted()) return false
        GamepadCalibrationService.start(context)
        GamepadCalibrationStateHolder.setActive(true)
        // 退到后台，让用户打开真正的游戏页面。
        goHome()
        return true
    }

    override fun dismiss() {
        GamepadCalibrationService.stop(context)
        GamepadCalibrationStateHolder.setActive(false)
    }

    private fun isPermissionGranted(): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    private fun goHome() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
