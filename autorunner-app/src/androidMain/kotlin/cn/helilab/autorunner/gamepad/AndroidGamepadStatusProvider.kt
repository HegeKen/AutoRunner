package cn.helilab.autorunner.gamepad

import com.autorunner.core.platform.AccessibilityController
import com.autorunner.gamepad.LocalGamepadGateway
import com.autorunner.ui.platform.GamepadStatusProvider

/**
 * Adapts the local gamepad injector to the UI's [GamepadStatusProvider].
 *
 * Gamepad simulation no longer talks to an external host, so there is no handshake
 * to perform: the feature is available whenever the accessibility service (the
 * channel used to inject the presses) is connected, and the only per-device setup
 * is mapping each button to an on-screen position in the settings.
 */
class AndroidGamepadStatusProvider(
    private val gateway: LocalGamepadGateway,
    private val accessibilityController: AccessibilityController,
) : GamepadStatusProvider {

    override val supported: Boolean get() = gateway.isSupported

    /** `true` while presses can actually be injected. */
    override val connected: Boolean get() = accessibilityController.isConnected

    /** No asynchronous handshake exists, so there is nothing to report. */
    override val lastError: String? get() = null

    override fun connect() = Unit

    override fun disconnect() = Unit
}
