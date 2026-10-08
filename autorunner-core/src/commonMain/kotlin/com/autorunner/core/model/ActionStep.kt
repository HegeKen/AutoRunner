package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A single recorded action.
 *
 * The JSON representation uses the `type` discriminator, matching the
 * `.arscript` specification:
 *
 * ```json
 * { "type": "tap", "x": 540, "y": 1200, "duration": 50, "delay": 500, "name": "登录按钮" }
 * ```
 *
 * Coordinates are pixels when the enclosing script uses
 * [CoordinateSpace.ABSOLUTE] and `0.0..1.0` fractions when it uses
 * [CoordinateSpace.NORMALIZED]; see [CoordinateResolver].
 *
 * [name] is an optional, user-defined label used by the editor to mark what a
 * step does for differentiation; the timeline falls back to [label] when null.
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ActionStep {

    /** Milliseconds to wait *after* this action completed. */
    val delay: Long

    /** Human readable one-liner used by the editor and the floating panel. */
    val label: String

    /** Short type name; mirrors the JSON discriminator. */
    val typeName: String

    /** Optional user-defined name. `null` falls back to [label] in the UI. */
    val name: String?
}

/** A single-finger tap. */
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

/** A press-and-hold. */
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

/** A straight-line drag. */
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

/** One contact of a [MultiTouchStep]. */
@Serializable
data class TouchPoint(
    val x: Float,
    val y: Float,
    /** Offset in milliseconds at which this pointer joined the gesture. */
    val startOffset: Long = 0L,
)

/** Two or more simultaneous contacts (pinch, two-finger scroll, …). */
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
 * A gamepad button / stick / trigger action, injected as a local gesture on this
 * device. Serialised as:
 *
 * ```json
 * { "type": "gamepad", "button": "A", "action": "press", "delay": 100 }
 * ```
 */
@Serializable
@SerialName("gamepad")
data class GamepadStep(
    val button: GamepadButton = GamepadButton.A,
    val action: GamepadAction = GamepadAction.PRESS,
    /** Analogue magnitude for stick / trigger actions, `0.0..1.0`. */
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
        const val DEFAULT_DELAY = 100L
    }
}

/** An explicit pause inside the flow. */
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
 * Type arbitrary text into the focused input field via the accessibility layer
 * (Android: `ACTION_SET_TEXT`). Used for scripts that fill in login / search /
 * registration forms.
 *
 * Serialised as:
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
 * Returns a copy of this action with a different trailing [delay].
 *
 * Used when the editor replaces a step with one captured from a real gesture while
 * keeping the delay the user had already configured.
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
 * Returns a copy of this action with a different [ActionStep.name].
 * Used by the editor when the user renames a step.
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
 * What to display in the timeline: the user-defined [ActionStep.name] when set,
 * otherwise the auto-generated [ActionStep.label].
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
