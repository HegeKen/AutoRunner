package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

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
    /**
     * 平板键盘映射：绑定的物理键盘按键码（Android `KeyEvent.KEYCODE_*`，
     * 平台无关地以纯 Int 存储）。开启键盘映射后，按下该键等价于在
     * [x]/[y] 位置点击一次；未绑定为 `null`。
     */
    val keyCode: Int? = null,
    /**
     * 键盘映射长按模式：为 `true` 时按下该键注入按住手势并保持指针按下，
     * 直到物理键抬起才收笔抬起（按住时长由实际按住时间决定，不再使用
     * [durationMs]）；为 `false` 时保持原有的固定时长一次性点击。
     */
    val holdMode: Boolean = false,
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
    /** 右摇杆中心（供「鼠标 / 触控板控制右摇杆」计算注入手势的起点）。 */
    val rightStickCenterX: Float = 0f,
    val rightStickCenterY: Float = 0f,
    val rightStickCenterConfigured: Boolean = false,
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
 * 允许绑定到手柄按键的物理键盘按键（键码取自 Android `KeyEvent.KEYCODE_*`，
 * 数值自 API 1 起即稳定，因此 common 层直接镜像常量、不依赖 Android 类型）。
 *
 * 只收录游戏常用键；HOME / BACK / 电源 / 音量等系统键刻意不在集合内，
 * 这样键盘桥接即便被打开也永远不会吞掉系统按键。
 */
object GamepadKeyLabels {

    /** [keyCode] 是否允许绑定（系统键会被拒绝）。 */
    fun isBindable(keyCode: Int): Boolean = labels.containsKey(keyCode)

    /** 键位的展示名；未知键码回退为中性描述。 */
    fun labelFor(keyCode: Int): String = labels[keyCode] ?: "按键 $keyCode"

    // Android KeyEvent 键码：数字 0=7..9=16；字母 A=29..Z=54；
    // 其余为游戏常用控制键（键码与 android.view.KeyEvent 常量一一对应）。
    private val labels: Map<Int, String> = mapOf(
        7 to "0", 8 to "1", 9 to "2", 10 to "3", 11 to "4",
        12 to "5", 13 to "6", 14 to "7", 15 to "8", 16 to "9",
        29 to "A", 30 to "B", 31 to "C", 32 to "D", 33 to "E", 34 to "F",
        35 to "G", 36 to "H", 37 to "I", 38 to "J", 39 to "K", 40 to "L",
        41 to "M", 42 to "N", 43 to "O", 44 to "P", 45 to "Q", 46 to "R",
        47 to "S", 48 to "T", 49 to "U", 50 to "V", 51 to "W", 52 to "X",
        53 to "Y", 54 to "Z",
        19 to "方向键 ↑",
        20 to "方向键 ↓",
        21 to "方向键 ←",
        22 to "方向键 →",
        24 to "音量 +",
        25 to "音量 -",
        55 to ", 逗号",
        56 to ". 句点",
        57 to "左 Alt",
        58 to "右 Alt",
        59 to "左 Shift",
        60 to "右 Shift",
        61 to "Tab",
        62 to "空格",
        66 to "回车",
        67 to "退格",
        68 to "` 反引号",
        69 to "- 减号",
        70 to "= 等号",
        71 to "[ 左括号",
        72 to "] 右括号",
        73 to "\\ 反斜杠",
        74 to "; 分号",
        75 to "' 单引号",
        76 to "/ 斜杠",
        77 to "@ At",
        81 to "+ 加号",
        92 to "Page Up",
        93 to "Page Down",
        111 to "Esc",
        112 to "Delete",
        113 to "左 Ctrl",
        114 to "右 Ctrl",
        115 to "Caps Lock",
        116 to "Scroll Lock",
        121 to "Pause/Break",
        123 to "End",
        125 to "Forward Delete",
        131 to "F1", 132 to "F2", 133 to "F3", 134 to "F4",
        135 to "F5", 136 to "F6", 137 to "F7", 138 to "F8",
        139 to "F9", 140 to "F10", 141 to "F11", 142 to "F12",
        143 to "? 问号",
        144 to "小键盘 0", 145 to "小键盘 1", 146 to "小键盘 2",
        147 to "小键盘 3", 148 to "小键盘 4", 149 to "小键盘 5",
        150 to "小键盘 6", 151 to "小键盘 7", 152 to "小键盘 8",
        153 to "小键盘 9",
        154 to "小键盘 /",
        155 to "小键盘 *",
        156 to "小键盘 -",
        157 to "小键盘 +",
        158 to "小键盘 .",
        159 to "小键盘 ,",
        160 to "小键盘回车",
    )
}

/**
 * 「WASD 控制左摇杆」的固定键位与方向合成。
 *
 * 键位固定无需逐键标定：W/A/S/D 按下集合被合成为一个**归一化**方向向量
 * （x 向右、y 向下，与屏幕坐标一致），调用方再用
 * [GamepadMappings.stickCenterX/Y] + [GamepadMappings.stickRadius]
 * 换算出摇杆边缘的目标位置。相邻两键（如 W+D）自然得到 45° 对角，
 * 相反两键（如 W+S）互相抵消。
 */
object GamepadStickKeys {

    /** Android `KeyEvent.KEYCODE_W`。 */
    const val KEY_W = 51

    /** Android `KeyEvent.KEYCODE_A`。 */
    const val KEY_A = 29

    /** Android `KeyEvent.KEYCODE_S`。 */
    const val KEY_S = 47

    /** Android `KeyEvent.KEYCODE_D`。 */
    const val KEY_D = 32

    /** 参与摇杆合成的全部键码。 */
    val All: Set<Int> = setOf(KEY_W, KEY_A, KEY_S, KEY_D)

    /**
     * 把当前按住的键合成为归一化方向向量。
     *
     * @return `x ∈ [-1, 1]`、`y ∈ [-1, 1]` 的单位向量（对角分量各约 ±0.707）；
     *   没有按键、全部相反抵消或集合里没有 WASD 任一键时返回 `null`（摇杆回中）。
     */
    fun directionOf(heldKeys: Set<Int>): Pair<Float, Float>? {
        var x = 0f
        var y = 0f
        if (KEY_W in heldKeys) y -= 1f
        if (KEY_S in heldKeys) y += 1f
        if (KEY_A in heldKeys) x -= 1f
        if (KEY_D in heldKeys) x += 1f
        if (x == 0f && y == 0f) return null
        val length = sqrt(x * x + y * y)
        return (x / length) to (y / length)
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
