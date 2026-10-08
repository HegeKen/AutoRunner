package cn.helilab.autorunner.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.autorunner.core.platform.GamepadCalibrationController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级的校准状态。
 *
 * 校准窗口存活于 [GamepadCalibrationService] 中，而状态需要被 Activity / 共享
 * UI 观察；用一个共享持有者让两者保持同步，无需绑定到该服务。
 */
object GamepadCalibrationStateHolder {

    private val _active = MutableStateFlow(false)

    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(value: Boolean) {
        _active.value = value
    }
}

/**
 * 由 [GamepadCalibrationService] 支撑的
 * `GamepadCalibrationController` 实现。
 *
 * 启用（arm）需要 `SYSTEM_ALERT_WINDOW` 权限；缺失时 [arm] 返回
 * `false`，调用方应向用户解释如何授予该权限。
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
