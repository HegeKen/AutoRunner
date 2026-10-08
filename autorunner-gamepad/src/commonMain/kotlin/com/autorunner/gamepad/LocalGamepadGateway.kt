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
 * 将按键**注入到本设备**的手柄模拟实现。
 *
 * 脚本中的 `gamepad` 动作会被转换为本地手势，并与录制的点击一样通过
 * 无障碍服务派发：
 *
 * | 脚本动作 | 本地效果 |
 * |---|---|
 * | `press` / `click` | 在该按键映射的位置上点击 |
 * | `release` | 空操作（点击本身就是瞬时的） |
 * | `trigger` | 时长随 `value` 缩放的长按 |
 * | `stick` | 从配置的摇杆中心向 `(x, y)` 拖动 |
 *
 * 正因如此，该功能才适用于自绘虚拟手柄的手机游戏：按键落在这台设备上的
 * 游戏里。不会向外部主机串流任何内容，因此无需蓝牙权限——早先的
 * 蓝牙 HID 实现之所以被移除，是因为它驱动的是*其他*设备。
 *
 * 该类刻意不依赖任何 Android 类型：它只需要共享的
 * [AccessibilityController] 和一份设置快照。
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

            // 一次点击无法跨越两个脚本动作保持按下，因此释放刻意设为空操作，
            // 而不是留下一个悬而未决的按下指针。
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
