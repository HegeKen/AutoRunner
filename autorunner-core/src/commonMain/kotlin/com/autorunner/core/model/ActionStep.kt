package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * 单个已录制的动作。
 *
 * JSON 表示使用 `type` 判别字段，与 `.arscript` 规范一致：
 *
 * ```json
 * { "type": "tap", "x": 540, "y": 1200, "duration": 50, "delay": 500, "name": "登录按钮" }
 * ```
 *
 * 外层脚本使用 [CoordinateSpace.ABSOLUTE] 时坐标为像素，使用
 * [CoordinateSpace.NORMALIZED] 时为 `0.0..1.0` 的比例值；参见 [CoordinateResolver]。
 *
 * [name] 是可选的用户自定义标签，编辑器用它标注步骤用途以作区分；
 * 为空时时间线回退到 [label]。
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ActionStep {

    /** 该动作完成后需要等待的毫秒数。 */
    val delay: Long

    /** 编辑器与悬浮面板使用的可读单行描述。 */
    val label: String

    /** 简短类型名，与 JSON 判别字段对应。 */
    val typeName: String

    /** 可选的用户自定义名称。为 `null` 时界面回退到 [label]。 */
    val name: String?
}

/** 单指点击。 */
@Serializable
@SerialName("tap")
data class TapStep(
    val x: Float,
    val y: Float,
    val duration: Long = DEFAULT_TAP_DURATION,
    override val delay: Long = DEFAULT_DELAY,
    override val name: String? = null,
) : ActionStep {
    override val label: String get() = "点击 (${x.toInt()}, ${y.toInt()}) ${duration}ms"
    override val typeName: String get() = "tap"

    companion object {
        const val DEFAULT_TAP_DURATION = 50L
        const val DEFAULT_DELAY = 300L
    }
}

/** 按住不放的长按。 */
@Serializable
@SerialName("longPress")
data class LongPressStep(
    val x: Float,
    val y: Float,
    val duration: Long = DEFAULT_LONG_PRESS_DURATION,
    override val delay: Long = DEFAULT_DELAY,
    override val name: String? = null,
) : ActionStep {
    override val label: String get() = "长按 (${x.toInt()}, ${y.toInt()}) ${duration}ms"
    override val typeName: String get() = "longPress"

    companion object {
        const val DEFAULT_LONG_PRESS_DURATION = 800L
        const val DEFAULT_DELAY = 300L
    }
}

/** 直线滑动。 */
@Serializable
@SerialName("swipe")
data class SwipeStep(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val duration: Long = DEFAULT_SWIPE_DURATION,
    override val delay: Long = DEFAULT_DELAY,
    override val name: String? = null,
) : ActionStep {
    override val label: String
        get() = "滑动 (${fromX.toInt()}, ${fromY.toInt()}) → (${toX.toInt()}, ${toY.toInt()}) ${duration}ms"

    override val typeName: String get() = "swipe"

    companion object {
        const val DEFAULT_SWIPE_DURATION = 300L
        const val DEFAULT_DELAY = 300L
    }
}

/** [MultiTouchStep] 中的一个触点。 */
@Serializable
data class TouchPoint(
    val x: Float,
    val y: Float,
    /** 该触点加入手势时的毫秒偏移。 */
    val startOffset: Long = 0L,
)

/** 两个及以上同时触点（捏合、双指滚动……）。 */
@Serializable
@SerialName("multiTouch")
data class MultiTouchStep(
    val points: List<TouchPoint>,
    val duration: Long = DEFAULT_MULTI_TOUCH_DURATION,
    override val delay: Long = DEFAULT_DELAY,
    override val name: String? = null,
) : ActionStep {
    override val label: String get() = "多点触控 ${points.size} 指 ${duration}ms"
    override val typeName: String get() = "multiTouch"

    companion object {
        const val DEFAULT_MULTI_TOUCH_DURATION = 300L
        const val DEFAULT_DELAY = 300L
    }
}

/**
 * 手柄按键 / 摇杆 / 扳机动作，作为本地手势注入到本设备。序列化为：
 *
 * ```json
 * { "type": "gamepad", "button": "A", "action": "press", "delay": 300 }
 * ```
 */
@Serializable
@SerialName("gamepad")
data class GamepadStep(
    val button: GamepadButton = GamepadButton.A,
    val action: GamepadAction = GamepadAction.PRESS,
    /** 摇杆 / 扳机动作的模拟量幅度，`0.0..1.0`。 */
    val value: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    override val delay: Long = DEFAULT_DELAY,
    override val name: String? = null,
) : ActionStep {
    override val label: String
        get() = if (action == GamepadAction.STICK) {
            "手柄摇杆 (${(x * 100).toInt()}%, ${(y * 100).toInt()}%)"
        } else buildString {
            append("手柄 ")
            append(button.serialName)
            append(' ')
            append(action.serialName)
            if (action.isAnalogue) append(" ${(value * 100).toInt()}%")
        }

    /**
     * 与 [label] 相同，但按键名按 [mode] 的品牌命名显示。
     *
     * `.arscript` 里存的是跨厂商的中立标识（`A` / `BACK` / `DPAD_UP`…），直接显示会
     * 与界面上按脚本手柄类型绘制的手柄图对不上：PS 手柄脚本里用户点的是「×」，列表
     * 却写着「手柄 A click」（真机反馈），Switch 手柄还会出现 A/B 互换的观感。
     * 凡是把动作展示给用户的地方都应使用本方法。
     */
    fun labelFor(mode: GamepadMode): String = if (action == GamepadAction.STICK) {
        label
    } else buildString {
        append("手柄 ")
        append(button.labelFor(mode))
        append(' ')
        append(action.serialName)
        if (action.isAnalogue) append(" ${(value * 100).toInt()}%")
    }

    override val typeName: String get() = "gamepad"

    companion object {
        const val DEFAULT_DELAY = 300L
    }
}

/** 流程中的一次显式停顿。 */
@Serializable
@SerialName("delay")
data class DelayStep(
    val duration: Long = 500L,
    override val delay: Long = 0L,
    override val name: String? = null,
) : ActionStep {
    override val label: String get() = "等待 ${duration}ms"
    override val typeName: String get() = "delay"
}

/**
 * 通过无障碍层向当前聚焦的输入框键入任意文本（Android：`ACTION_SET_TEXT`）。
 * 用于填写登录 / 搜索 / 注册表单的脚本。
 *
 * 序列化为：
 *
 * ```json
 * { "type": "key", "text": "Hello", "delay": 300 }
 * ```
 */
@Serializable
@SerialName("key")
data class KeyStep(
    val text: String,
    override val delay: Long = DEFAULT_DELAY,
    override val name: String? = null,
) : ActionStep {
    override val label: String
        get() = buildString {
            append("键盘输入 ")
            append('“')
            val preview = if (text.length <= 20) text else "${text.take(20)}…"
            append(preview)
            append('”')
        }

    override val typeName: String get() = "key"

    companion object {
        const val DEFAULT_DELAY = 300L
    }
}

/**
 * 返回把结尾 [delay] 改为新值后的动作副本。
 *
 * 当编辑器用真实手势捕获的步骤替换原有步骤、同时保留用户已配置的延时时使用。
 */
fun ActionStep.withDelay(newDelay: Long): ActionStep = when (this) {
    is TapStep -> copy(delay = newDelay)
    is LongPressStep -> copy(delay = newDelay)
    is SwipeStep -> copy(delay = newDelay)
    is MultiTouchStep -> copy(delay = newDelay)
    is GamepadStep -> copy(delay = newDelay)
    is DelayStep -> copy(delay = newDelay)
    is KeyStep -> copy(delay = newDelay)
}

/**
 * 返回改了 [ActionStep.name] 的动作副本。
 * 用户重命名步骤时由编辑器使用。
 */
fun ActionStep.withName(newName: String?): ActionStep {
    val trimmed = newName?.trim()?.ifBlank { null }
    return when (this) {
        is TapStep -> copy(name = trimmed)
        is LongPressStep -> copy(name = trimmed)
        is SwipeStep -> copy(name = trimmed)
        is MultiTouchStep -> copy(name = trimmed)
        is GamepadStep -> copy(name = trimmed)
        is DelayStep -> copy(name = trimmed)
        is KeyStep -> copy(name = trimmed)
    }
}

/**
 * 时间线里显示什么：设置了用户自定义的 [ActionStep.name] 就用它，
 * 否则用自动生成的 [ActionStep.label]。
 */
val ActionStep.displayLabel: String
    get() = name?.takeIf { it.isNotBlank() } ?: label

/**
 * 与 [label] 相同，但手柄动作的按键按 [mode] 的品牌命名显示。
 *
 * 时间线 / 详情 / 悬浮面板都应该用它而不是 [ActionStep.label]：中立标识只属于
 * `.arscript` 文件格式，不该出现在界面上。
 */
fun ActionStep.labelFor(mode: GamepadMode): String = when (this) {
    is GamepadStep -> labelFor(mode)
    else -> label
}

/** [displayLabel]，手柄动作的按键按 [mode] 的品牌命名显示。 */
fun ActionStep.displayLabel(mode: GamepadMode): String =
    name?.takeIf { it.isNotBlank() } ?: labelFor(mode)
