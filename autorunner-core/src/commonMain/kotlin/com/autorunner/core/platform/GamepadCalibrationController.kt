package com.autorunner.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the on-screen gamepad calibration flow.
 *
 * When a script editor needs gamepad coordinates the user is sent into the real
 * game page with a floating gamepad entry ball. Tapping the ball reveals every
 * button as a draggable chip so the user can line them up with the actual
 * on-screen controls; the result is stored in the global gamepad mappings.
 */
interface GamepadCalibrationController {

    /** `true` while the floating calibration layer is alive. */
    val active: StateFlow<Boolean>

    /**
     * Arms the calibration overlay and sends the app to the background so the
     * user can open the real game page.
     *
     * @return `false` when the overlay cannot be shown (missing overlay permission).
     */
    fun arm(): Boolean

    /** Tears the calibration overlay down. */
    fun dismiss()
}

/** Fallback controller used when the platform cannot show overlays. */
class UnavailableGamepadCalibrationController(
    private val reason: String = "当前平台不支持手柄标定",
) : GamepadCalibrationController {

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Populated so the UI can explain why calibration is unavailable. */
    var lastError: String? = reason
        private set

    override fun arm(): Boolean = false

    override fun dismiss() {
        _active.value = false
    }
}
