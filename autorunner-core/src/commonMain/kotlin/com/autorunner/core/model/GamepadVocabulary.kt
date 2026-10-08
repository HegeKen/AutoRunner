package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 模拟手柄暴露的按键。`serialName` 是存储在 `.arscript` 文件里的跨厂商中立
 * 标识；每种 HID 配置把它映射到自己的报告描述符位。
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

    /** 选择 [mode] 后屏幕手柄上显示的品牌专属标注。 */
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

/** 模拟手柄的模拟量轴。 */
@Serializable
enum class TriggerKey(val serialName: String) {
    @SerialName("LEFT_STICK_X") LEFT_STICK_X("LEFT_STICK_X"),
    @SerialName("LEFT_STICK_Y") LEFT_STICK_Y("LEFT_STICK_Y"),
    @SerialName("RIGHT_STICK_X") RIGHT_STICK_X("RIGHT_STICK_X"),
    @SerialName("RIGHT_STICK_Y") RIGHT_STICK_Y("RIGHT_STICK_Y"),
    @SerialName("LEFT_TRIGGER") LEFT_TRIGGER("LEFT_TRIGGER"),
    @SerialName("RIGHT_TRIGGER") RIGHT_TRIGGER("RIGHT_TRIGGER"),
}

/** 手柄交互的类型。 */
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
 * 单个手柄按键在屏幕上的位置。
 *
 * 手柄模拟把按下动作**注入到本设备**（在游戏的虚拟手柄上本地触控），
 * 因此每个按键都需要知道自己在屏幕上的位置。坐标为当前设备的绝对像素，
 * 与录制器产出的坐标一致。
 */
@Serializable
data class GamepadButtonMapping(
    val button: GamepadButton,
    val x: Float = 0f,
    val y: Float = 0f,
    /** 合成按下动作保持的时长。 */
    val durationMs: Long = 60L,
    /** 用户为该按键选定位置之前为 `false`。 */
    val configured: Boolean = false,
)

/** 完整的本地手柄配置。 */
@Serializable
data class GamepadMappings(
    val buttons: List<GamepadButtonMapping> = emptyList(),
    /** 虚拟摇杆中心，供 `stick` 动作使用。 */
    val stickCenterX: Float = 0f,
    val stickCenterY: Float = 0f,
    val stickCenterConfigured: Boolean = false,
    /** 满偏时的行程像素数（`x`/`y` = ±1）。 */
    val stickRadius: Float = 220f,
    /**
     * 用户在本设备上完成过一次性整柄校准后为 `true`。按键位置是设备的
     * 属性，之后脚本编辑器不再要求校准。
     */
    val calibrated: Boolean = false,
) {
    /** [button] 的映射；用户尚未配置时为 `null`。 */
    fun mappingFor(button: GamepadButton): GamepadButtonMapping? =
        buttons.firstOrNull { it.button == button && it.configured }

    /** 替换（或新增）一个按键映射。 */
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
 * 脚本执行器用来运行 `gamepad` 动作的平台无关入口。
 *
 * AutoRunner 驱动的是**本设备**：`gamepad` 步骤会被转换成在用户为该按键
 * 映射的位置上的本地触控（或摇杆拖动），并像其他手势一样经无障碍服务派发。
 * 不会向外部主机发送任何内容，因此不涉及蓝牙权限。
 */
interface GamepadGateway {
    /** 本设备支持本地手柄注入时为 `true`。 */
    val isSupported: Boolean

    /** 注入所需的无障碍服务处于已连接状态时为 `true`。 */
    val isConnected: Boolean

    /** 尽力而为的准备；无法注入时返回 `false`。 */
    suspend fun connect(): Boolean

    /** 释放网关持有的所有状态。 */
    fun disconnect()

    /**
     * 执行脚本中的一个 `gamepad` 步骤。
     *
     * @param mode 使用哪一套按模式的校准，取自脚本的
     *   [com.autorunner.core.model.ScriptInfo.gamepadMode]。
     * @throws IllegalStateException 当按键（或摇杆中心）尚未映射，
     *   或设置里禁用了手柄模拟时。
     */
    suspend fun execute(step: GamepadStep, mode: GamepadMode)
}
