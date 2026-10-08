package com.autorunner.gamepad

import com.autorunner.core.model.AppSettings
import com.autorunner.core.model.GamepadAction
import com.autorunner.core.model.GamepadGateway
import com.autorunner.core.model.GamepadMappings
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.labelFor
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.ActionResult

/**
 * Gamepad simulation that injects the buttons **into this device**.
 *
 * A `gamepad` action in a script is translated into a local gesture and dispatched
 * through the accessibility service exactly like a recorded tap:
 *
 * | script action | local effect |
 * |---|---|
 * | `press` / `click` | tap at the mapped position of that button |
 * | `release` | no-op (a tap is already instantaneous) |
 * | `trigger` | long press whose duration scales with `value` |
 * | `stick` | drag from the configured stick centre towards `(x, y)` |
 *
 * This is what makes the feature usable for phone games that render their own
 * virtual pad: the press lands on the game, on this device. Nothing is streamed to
 * an external console, so no Bluetooth permission is required — the earlier
 * Bluetooth HID implementation was removed because it drove *other* devices.
 *
 * The class is deliberately free of Android types: it only needs the shared
 * [AccessibilityController] and a settings snapshot.
 */
class LocalGamepadGateway(
    private val accessibilityController: AccessibilityController,
    private val settingsProvider: () -> AppSettings,
) : GamepadGateway {

    override val isSupported: Boolean = true

    override val isConnected: Boolean get() = accessibilityController.isConnected

    override suspend fun connect(): Boolean = accessibilityController.isConnected

    override fun disconnect() = Unit

    override suspend fun execute(step: GamepadStep, mode: GamepadMode) {
        val settings = settingsProvider()
        check(settings.gamepadEnabled) { "手柄模拟未启用" }
        check(accessibilityController.isConnected) { "无障碍服务未连接，无法注入手柄按键" }

        val mappings = settings.gamepadMappingsFor(mode)
        when (step.action) {
            GamepadAction.STICK -> performStick(step, mappings)

            GamepadAction.TRIGGER -> {
                val mapping = requireMapping(step, mappings, mode)
                val duration = (mapping.durationMs * step.value.coerceIn(0f, 1f))
                    .toLong()
                    .coerceAtLeast(MIN_TRIGGER_DURATION_MS)
                dispatch(LongPressStep(x = mapping.x, y = mapping.y, duration = duration, delay = step.delay))
            }

            GamepadAction.PRESS, GamepadAction.CLICK -> {
                val mapping = requireMapping(step, mappings, mode)
                dispatch(
                    TapStep(
                        x = mapping.x,
                        y = mapping.y,
                        duration = mapping.durationMs.coerceAtLeast(MIN_TAP_DURATION_MS),
                        delay = step.delay,
                    ),
                )
            }

            // A tap cannot be held open across two script actions, so releasing is
            // intentionally a no-op instead of leaving a dangling pointer down.
            GamepadAction.RELEASE -> Unit
        }
    }

    private suspend fun performStick(step: GamepadStep, mappings: GamepadMappings) {
        check(mappings.stickCenterConfigured) { "尚未设置虚拟摇杆中心" }
        val targetX = mappings.stickCenterX + step.x.coerceIn(-1f, 1f) * mappings.stickRadius
        val targetY = mappings.stickCenterY + step.y.coerceIn(-1f, 1f) * mappings.stickRadius
        dispatch(
            SwipeStep(
                fromX = mappings.stickCenterX,
                fromY = mappings.stickCenterY,
                toX = targetX,
                toY = targetY,
                duration = STICK_DRAG_DURATION_MS,
                delay = step.delay,
            ),
        )
    }

    private fun requireMapping(
        step: GamepadStep,
        mappings: GamepadMappings,
        mode: GamepadMode,
    ) = mappings.mappingFor(step.button)
        // 报错文案用脚本手柄类型的品牌命名（`.arscript` 里的中立标识不面向用户）。
        ?: error("手柄按键 ${step.button.labelFor(mode)} 尚未映射屏幕位置")

    private suspend fun dispatch(step: com.autorunner.core.model.ActionStep) {
        when (val result = accessibilityController.perform(step)) {
            is ActionResult.Success -> Unit
            is ActionResult.Failure -> error(result.reason)
            is ActionResult.Unsupported -> error(result.reason)
        }
    }

    private companion object {
        const val MIN_TAP_DURATION_MS = 40L
        const val MIN_TRIGGER_DURATION_MS = 50L
        const val STICK_DRAG_DURATION_MS = 220L
    }
}
