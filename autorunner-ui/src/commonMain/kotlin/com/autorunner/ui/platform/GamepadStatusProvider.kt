package com.autorunner.ui.platform

/**
 * Read-only view of the optional gamepad module.
 *
 * `autorunner-ui` deliberately does not depend on `autorunner-gamepad`; the app
 * module adapts `LocalGamepadGateway` to this interface (and to
 * `com.autorunner.core.model.GamepadGateway`, which the executor uses).
 */
interface GamepadStatusProvider {

    /** `true` when local gamepad injection is possible on this device. */
    val supported: Boolean

    /** `true` while a host is connected. */
    val connected: Boolean

    /** Last failure reported by the platform, if any. */
    val lastError: String?

    /** Advertises the HID profile in the background. */
    fun connect()

    /** Stops advertising / disconnects. */
    fun disconnect()
}
