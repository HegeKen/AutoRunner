package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.model.GamepadButton
import com.autorunner.core.model.GamepadButtonMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一个可拖拽的标定节点：要么是一个手柄按键，要么是虚拟摇杆的中心。
 *
 * [x] / [y] 是该节点中心在屏幕上的绝对像素坐标，与录制输出的坐标系一致。
 * [button] 为 `null` 时表示摇杆中心。
 */
data class CalibrationTarget(
    val id: String,
    val label: String,
    val button: GamepadButton?,
    val x: Float,
    val y: Float,
) {
    val isStickCenter: Boolean get() = button == null
}

/**
 * 手柄按键标定的状态持有者。
 *
 * 用户进入实际游戏页面后，悬浮层把全部按键（17 个 + 摇杆中心，共 18 个）铺在屏幕上，
 * 用户把它们拖到游戏里真实按钮的位置，保存后写入当前手柄类型对应的那一份标定
 * (`AppSettings.gamepadMappingsByMode`)，之后新增/编辑同类型手柄动作只需选按键、
 * 无需再输入坐标。
 */
class GamepadCalibrationViewModel(
    private val container: AppContainer,
) {

    private val _targets = MutableStateFlow<List<CalibrationTarget>>(emptyList())
    val targets: StateFlow<List<CalibrationTarget>> = _targets.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var canvasWidthPx: Float = 0f
    private var canvasHeightPx: Float = 0f

    /** 画布尺寸已知后调用；幂等——已铺开布局时不会覆盖用户的拖动结果。 */
    fun ensureLayout(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        canvasWidthPx = widthPx
        canvasHeightPx = heightPx
        if (_targets.value.isNotEmpty()) return
        _targets.value = buildDefaultLayout(widthPx, heightPx)
    }

    /** 把布局恢复到默认网格（丢弃当前拖动结果）。 */
    fun resetLayout() {
        if (canvasWidthPx <= 0f || canvasHeightPx <= 0f) return
        _targets.value = buildDefaultLayout(canvasWidthPx, canvasHeightPx)
        _message.value = "已恢复默认布局"
    }

    /** 按像素增量移动一个节点。 */
    fun moveTarget(id: String, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        _targets.value = _targets.value.map { target ->
            if (target.id != id) {
                target
            } else {
                target.copy(
                    x = (target.x + dx).coerceIn(0f, canvasWidthPx),
                    y = (target.y + dy).coerceIn(0f, canvasHeightPx),
                )
            }
        }
    }

    /** 把当前拖拽结果写入当前手柄类型对应的那一份标定，并启用模拟手柄。 */
    fun save() {
        val targets = _targets.value
        if (targets.isEmpty()) {
            _message.value = "没有可保存的按键位置"
            return
        }
        container.settingsRepository.update { settings ->
            val mode = settings.gamepadMode
            var mappings = settings.gamepadMappingsFor(mode).copy(calibrated = true)
            targets.forEach { target ->
                val button = target.button
                if (button == null) {
                    mappings = mappings.copy(
                        stickCenterX = target.x,
                        stickCenterY = target.y,
                        stickCenterConfigured = true,
                    )
                } else {
                    val duration = mappings.buttons
                        .firstOrNull { it.button == button }?.durationMs ?: 60L
                    mappings = mappings.withMapping(
                        GamepadButtonMapping(
                            button = button,
                            x = target.x,
                            y = target.y,
                            durationMs = duration,
                            configured = true,
                        ),
                    )
                }
            }
            settings.copy(
                gamepadMappingsByMode = settings.gamepadMappingsByMode + (mode to mappings),
                gamepadEnabled = true,
            )
        }
        _message.value = "已保存手柄按键位置"
    }

    fun dismissMessage() {
        _message.value = null
    }

    // --------------------------------------------------------------- layout

    private fun buildDefaultLayout(widthPx: Float, heightPx: Float): List<CalibrationTarget> {
        val settings = container.settingsRepository.current
        val existing = settings.currentGamepadMappings
        val mode = settings.gamepadMode
        val nodes = GamepadButton.entries.map { button ->
            NodeSpec(id = button.serialName, label = button.labelFor(mode), button = button)
        } + NodeSpec(id = STICK_CENTER_ID, label = "◎", button = null)

        return nodes.map { node ->
            val configured = resolveConfigured(node.button, existing)
            val fraction = defaultFraction(node.button)
            CalibrationTarget(
                id = node.id,
                label = node.label,
                button = node.button,
                x = configured?.first ?: (fraction.first * widthPx),
                y = configured?.second ?: (fraction.second * heightPx),
            )
        }
    }

    /**
     * 默认排布比例（相对屏幕宽/高），按 PS Remote Play 原生虚拟手柄（DualSense）
     * 的真实键位铺开，取代此前的顺序网格。比例取自 2608x1200 横屏全屏截图上量得的
     * 按键中心：左肩 L2/L1、右肩 R2/R1、左十字键、右 △□○×、底部 BACK/GUIDE/START、
     * 摇杆 L3/R3 及摇杆中心。
     *
     * 三个手柄模式共用同一套物理位置——屏幕上的落点由 Remote Play 的虚拟手柄覆盖层
     * 决定，与手柄品牌无关；品牌差异只体现在标签上，由 [GamepadButton.labelFor] 给出。
     */
    private fun defaultFraction(button: GamepadButton?): Pair<Float, Float> = when (button) {
        null -> STICK_CENTER_FRACTION
        GamepadButton.LT -> 0.137f to 0.142f
        GamepadButton.RT -> 0.918f to 0.142f
        GamepadButton.LB -> 0.203f to 0.264f
        GamepadButton.RB -> 0.851f to 0.263f
        GamepadButton.Y -> 0.894f to 0.471f
        GamepadButton.X -> 0.829f to 0.613f
        GamepadButton.B -> 0.959f to 0.613f
        GamepadButton.A -> 0.894f to 0.754f
        GamepadButton.DPAD_UP -> 0.157f to 0.493f
        GamepadButton.DPAD_DOWN -> 0.157f to 0.731f
        GamepadButton.DPAD_LEFT -> 0.102f to 0.612f
        GamepadButton.DPAD_RIGHT -> 0.211f to 0.612f
        GamepadButton.L3 -> 0.317f to 0.785f
        GamepadButton.R3 -> 0.712f to 0.788f
        GamepadButton.BACK -> 0.426f to 0.958f
        GamepadButton.GUIDE -> 0.523f to 0.955f
        GamepadButton.START -> 0.631f to 0.931f
    }

    private fun resolveConfigured(
        button: GamepadButton?,
        mappings: com.autorunner.core.model.GamepadMappings,
    ): Pair<Float, Float>? {
        if (button == null) {
            return if (mappings.stickCenterConfigured) {
                Pair(mappings.stickCenterX, mappings.stickCenterY)
            } else {
                null
            }
        }
        val mapping = mappings.mappingFor(button) ?: return null
        return Pair(mapping.x, mapping.y)
    }

    private data class NodeSpec(
        val id: String,
        val label: String,
        val button: GamepadButton?,
    )

    companion object {
        const val STICK_CENTER_ID = "STICK_CENTER"

        /** 摇杆中心默认比例，与左摇杆（L3）的物理位置一致。 */
        private val STICK_CENTER_FRACTION = 0.317f to 0.785f
    }
}
