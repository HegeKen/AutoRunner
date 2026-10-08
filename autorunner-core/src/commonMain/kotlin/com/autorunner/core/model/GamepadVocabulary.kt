package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Buttons exposed by the simulated gamepads. The `serialName` values are the
 * neutral, cross-vendor identifiers stored inside `.arscript` files; each HID
 * profile maps them onto its own report descriptor bit.
 */
@Serializable
enum class GamepadButton(val serialName: String, val displayName: String) {
    @SerialName("A") A("A", "A / ×"),
    @SerialName("B") B("B", "B / ○"),
    @SerialName("X") X("X", "X / □"),
    @SerialName("Y") Y("Y", "Y / △"),
    @SerialName("LB") LB("LB", "LB (L1)"),
    @SerialName("RB") RB("RB", "RB (R1)"),
    @SerialName("LT") LT("LT", "LT (L2)"),
    @SerialName("RT") RT("RT", "RT (R2)"),
    @SerialName("BACK") BACK("BACK", "Back / Share"),
    @SerialName("START") START("START", "Start / Options"),
    @SerialName("GUIDE") GUIDE("GUIDE", "Guide / PS"),
    @SerialName("L3") L3("L3", "LS click"),
    @SerialName("R3") R3("R3", "RS click"),
    @SerialName("DPAD_UP") DPAD_UP("DPAD_UP", "方向键 ↑"),
    @SerialName("DPAD_DOWN") DPAD_DOWN("DPAD_DOWN", "方向键 ↓"),
    @SerialName("DPAD_LEFT") DPAD_LEFT("DPAD_LEFT", "方向键 ←"),
    @SerialName("DPAD_RIGHT") DPAD_RIGHT("DPAD_RIGHT", "方向键 →"),
    ;

    companion object {
        fun fromSerialName(value: String): GamepadButton? =
            entries.firstOrNull { it.serialName.equals(value, ignoreCase = true) }
    }

    /** Brand specific caption shown on the on-screen pad when [mode] is selected. */
    fun labelFor(mode: GamepadMode): String = when (mode) {
        GamepadMode.XBOX -> when (this) {
            A -> "A"
            B -> "B"
            X -> "X"
            Y -> "Y"
            LB -> "LB"
            RB -> "RB"
            LT -> "LT"
            RT -> "RT"
            BACK -> "View"
            START -> "Menu"
            GUIDE -> "Guide"
            L3 -> "LS"
            R3 -> "RS"
            DPAD_UP -> "↑"
            DPAD_DOWN -> "↓"
            DPAD_LEFT -> "←"
            DPAD_RIGHT -> "→"
        }
        GamepadMode.DUALSENSE -> when (this) {
            // 用 PlayStation 自己的符号：叉是 U+00D7（不是拉丁字母 X，否则会被
            // 当成 Xbox 的 X 键），圈是 U+25CB（不是全角 U+3007）。
            A -> "×"
            B -> "○"
            X -> "□"
            Y -> "△"
            LB -> "L1"
            RB -> "R1"
            LT -> "L2"
            RT -> "R2"
            BACK -> "Share"
            START -> "Options"
            GUIDE -> "PS"
            L3 -> "L3"
            R3 -> "R3"
            DPAD_UP -> "↑"
            DPAD_DOWN -> "↓"
            DPAD_LEFT -> "←"
            DPAD_RIGHT -> "→"
        }
        GamepadMode.SWITCH_PRO -> when (this) {
            A -> "B"
            B -> "A"
            X -> "Y"
            Y -> "X"
            LB -> "L"
            RB -> "R"
            LT -> "ZL"
            RT -> "ZR"
            BACK -> "Minus"
            START -> "Plus"
            GUIDE -> "Home"
            L3 -> "L3"
            R3 -> "R3"
            DPAD_UP -> "↑"
            DPAD_DOWN -> "↓"
            DPAD_LEFT -> "←"
            DPAD_RIGHT -> "→"
        }
    }
}

/** Analogue axes of the simulated gamepads. */
@Serializable
enum class TriggerKey(val serialName: String) {
    @SerialName("LEFT_STICK_X") LEFT_STICK_X("LEFT_STICK_X"),
    @SerialName("LEFT_STICK_Y") LEFT_STICK_Y("LEFT_STICK_Y"),
    @SerialName("RIGHT_STICK_X") RIGHT_STICK_X("RIGHT_STICK_X"),
    @SerialName("RIGHT_STICK_Y") RIGHT_STICK_Y("RIGHT_STICK_Y"),
    @SerialName("LEFT_TRIGGER") LEFT_TRIGGER("LEFT_TRIGGER"),
    @SerialName("RIGHT_TRIGGER") RIGHT_TRIGGER("RIGHT_TRIGGER"),
}

/** Kind of gamepad interaction. */
@Serializable
enum class GamepadAction(val serialName: String) {
    @SerialName("press") PRESS("press"),
    @SerialName("release") RELEASE("release"),
    @SerialName("click") CLICK("click"),
    @SerialName("stick") STICK("stick"),
    @SerialName("trigger") TRIGGER("trigger"),
    ;

    val isAnalogue: Boolean get() = this == STICK || this == TRIGGER

    companion object {
        fun fromSerialName(value: String): GamepadAction? =
            entries.firstOrNull { it.serialName.equals(value, ignoreCase = true) }
    }
}

/**
 * On-screen position of one gamepad button.
 *
 * Gamepad simulation injects the press **into this device** (a local touch on the
 * game's virtual pad), so every button needs to know where that button lives on
 * screen. Coordinates are absolute pixels of the current device, matching what
 * the recorder produces.
 */
@Serializable
data class GamepadButtonMapping(
    val button: GamepadButton,
    val x: Float = 0f,
    val y: Float = 0f,
    /** How long the synthetic press is held. */
    val durationMs: Long = 60L,
    /** `false` until the user picks a position for this button. */
    val configured: Boolean = false,
)

/** Complete local gamepad configuration. */
@Serializable
data class GamepadMappings(
    val buttons: List<GamepadButtonMapping> = emptyList(),
    /** Centre of the virtual joystick, used by `stick` actions. */
    val stickCenterX: Float = 0f,
    val stickCenterY: Float = 0f,
    val stickCenterConfigured: Boolean = false,
    /** Travel in pixels for a full deflection (`x`/`y` = ±1). */
    val stickRadius: Float = 220f,
    /**
     * `true` once the user has run the one-shot whole-pad calibration on this
     * device. Button positions are a property of the device, so the script
     * editor stops asking for calibration afterwards.
     */
    val calibrated: Boolean = false,
) {
    /** The mapping of [button], or `null` when the user has not configured it. */
    fun mappingFor(button: GamepadButton): GamepadButtonMapping? =
        buttons.firstOrNull { it.button == button && it.configured }

    /** Replaces (or adds) one button mapping. */
    fun withMapping(mapping: GamepadButtonMapping): GamepadMappings =
        copy(buttons = buttons.filterNot { it.button == mapping.button } + mapping)

    fun withoutMapping(button: GamepadButton): GamepadMappings =
        copy(buttons = buttons.filterNot { it.button == button })

    val configuredCount: Int get() = buttons.count { it.configured }

    companion object {
        val Empty = GamepadMappings()
    }
}

/**
 * Platform-agnostic entry point used by the script executor to run `gamepad`
 * actions.
 *
 * AutoRunner drives **this** device: a `gamepad` step is translated into a local
 * touch (or a stick drag) at the position the user mapped for that button, and
 * dispatched through the accessibility service like any other gesture. Nothing is
 * sent to an external host, so no Bluetooth permission is involved.
 */
interface GamepadGateway {
    /** `true` when local gamepad injection is possible on this device. */
    val isSupported: Boolean

    /** `true` while the accessibility service needed for injection is connected. */
    val isConnected: Boolean

    /** Best effort preparation; returns `false` when injection is impossible. */
    suspend fun connect(): Boolean

    /** Releases any state held by the gateway. */
    fun disconnect()

    /**
     * Executes a `gamepad` step from a script.
     *
     * @param mode which per-mode calibration to use, taken from the script's
     *   [com.autorunner.core.model.ScriptInfo.gamepadMode].
     * @throws IllegalStateException when the button (or the stick centre) has not
     *   been mapped yet, or when gamepad simulation is disabled in the settings.
     */
    suspend fun execute(step: GamepadStep, mode: GamepadMode)
}
