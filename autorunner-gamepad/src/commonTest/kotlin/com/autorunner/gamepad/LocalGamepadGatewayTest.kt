package com.autorunner.gamepad

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.AppSettings
import com.autorunner.core.model.GamepadAction
import com.autorunner.core.model.GamepadButton
import com.autorunner.core.model.GamepadButtonMapping
import com.autorunner.core.model.GamepadMappings
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.ActionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gamepad simulation injects presses into *this* device, so the tests assert the
 * translated local gestures (not any Bluetooth traffic).
 */
class LocalGamepadGatewayTest {

    private class FakeController(override var isConnected: Boolean = true) : AccessibilityController {
        val dispatched = mutableListOf<ActionStep>()
        override val supportsGestures = true
        override val screenMetrics = ScreenMetrics(1080, 2400, 2.75f)
        override fun refreshScreenMetrics() = screenMetrics
        override suspend fun perform(step: ActionStep): ActionResult {
            if (!isConnected) return ActionResult.Failure("无障碍服务未连接", recoverable = true)
            dispatched += step
            return ActionResult.Success
        }

        override fun cancelPendingGestures() = Unit
    }

    private fun settings(
        enabled: Boolean = true,
        mappings: GamepadMappings = GamepadMappings.Empty,
    ) = AppSettings(
        gamepadEnabled = enabled,
        gamepadMappingsByMode = mapOf(GamepadMode.XBOX to mappings),
    )

    private fun mapping(button: GamepadButton, x: Float, y: Float, duration: Long = 60L) =
        GamepadButtonMapping(button = button, x = x, y = y, durationMs = duration, configured = true)

    @Test
    fun pressBecomesALocalTapAtTheMappedPosition() = runTest {
        val controller = FakeController()
        val gateway = LocalGamepadGateway(controller) {
            settings(mappings = GamepadMappings(buttons = listOf(mapping(GamepadButton.A, 300f, 1800f))))
        }

        gateway.execute(GamepadStep(button = GamepadButton.A, action = GamepadAction.PRESS, delay = 120L), GamepadMode.XBOX)

        val tap = controller.dispatched.single() as TapStep
        assertEquals(300f, tap.x)
        assertEquals(1800f, tap.y)
        assertEquals(120L, tap.delay)
    }

    @Test
    fun clickAndPressAreEquivalentAndReleaseIsANoOp() = runTest {
        val controller = FakeController()
        val gateway = LocalGamepadGateway(controller) {
            settings(mappings = GamepadMappings(buttons = listOf(mapping(GamepadButton.B, 10f, 20f))))
        }

        gateway.execute(GamepadStep(button = GamepadButton.B, action = GamepadAction.CLICK), GamepadMode.XBOX)
        gateway.execute(GamepadStep(button = GamepadButton.B, action = GamepadAction.RELEASE), GamepadMode.XBOX)

        assertEquals(1, controller.dispatched.size)
        assertTrue(controller.dispatched.single() is TapStep)
    }

    @Test
    fun triggerScalesThePressDurationWithTheAnalogueValue() = runTest {
        val controller = FakeController()
        val gateway = LocalGamepadGateway(controller) {
            settings(mappings = GamepadMappings(buttons = listOf(mapping(GamepadButton.RT, 5f, 6f, duration = 400L))))
        }

        gateway.execute(GamepadStep(button = GamepadButton.RT, action = GamepadAction.TRIGGER, value = 0.5f), GamepadMode.XBOX)

        val press = controller.dispatched.single() as LongPressStep
        assertEquals(200L, press.duration)
    }

    @Test
    fun stickDragsFromTheConfiguredCentreTowardsTheRequestedDirection() = runTest {
        val controller = FakeController()
        val gateway = LocalGamepadGateway(controller) {
            settings(
                mappings = GamepadMappings(
                    stickCenterX = 200f,
                    stickCenterY = 1600f,
                    stickCenterConfigured = true,
                    stickRadius = 100f,
                ),
            )
        }

        gateway.execute(GamepadStep(action = GamepadAction.STICK, x = 1f, y = -0.5f, value = 1f), GamepadMode.XBOX)

        val drag = controller.dispatched.single() as SwipeStep
        assertEquals(200f, drag.fromX)
        assertEquals(1600f, drag.fromY)
        assertEquals(300f, drag.toX)
        assertEquals(1550f, drag.toY)
    }

    @Test
    fun unmappedButtonsFailFastWithAnActionableMessage() = runTest {
        val gateway = LocalGamepadGateway(FakeController()) { settings(mappings = GamepadMappings.Empty) }

        val error = assertFailsWith<IllegalStateException> {
            gateway.execute(GamepadStep(button = GamepadButton.Y, action = GamepadAction.PRESS), GamepadMode.XBOX)
        }

        assertTrue(error.message!!.contains("Y"))
        assertTrue(error.message!!.contains("尚未映射"))
    }

    @Test
    fun stickWithoutCentreIsRejected() = runTest {
        val gateway = LocalGamepadGateway(FakeController()) { settings() }

        assertFailsWith<IllegalStateException> {
            gateway.execute(GamepadStep(action = GamepadAction.STICK, x = 0f, y = 1f), GamepadMode.XBOX)
        }
    }

    @Test
    fun disabledSimulationIsRejected() = runTest {
        val gateway = LocalGamepadGateway(FakeController()) {
            settings(enabled = false, mappings = GamepadMappings(buttons = listOf(mapping(GamepadButton.A, 1f, 1f))))
        }

        assertFailsWith<IllegalStateException> {
            gateway.execute(GamepadStep(button = GamepadButton.A, action = GamepadAction.PRESS), GamepadMode.XBOX)
        }
    }

    @Test
    fun aDisconnectedAccessibilityServiceIsReported() = runTest {
        val gateway = LocalGamepadGateway(FakeController(isConnected = false)) {
            settings(mappings = GamepadMappings(buttons = listOf(mapping(GamepadButton.A, 1f, 1f))))
        }

        assertFailsWith<IllegalStateException> {
            gateway.execute(GamepadStep(button = GamepadButton.A, action = GamepadAction.PRESS), GamepadMode.XBOX)
        }
    }
}
