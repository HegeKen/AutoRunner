package com.autorunner.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手柄按键的「中立标识 → 品牌命名」映射。
 *
 * `.arscript` 里只存中立标识（`A` / `BACK` / `DPAD_UP`…），界面必须按脚本的
 * 手柄类型渲染，否则 PS 手柄脚本里被点中的「×」会显示成「手柄 A click」。
 */
class GamepadVocabularyTest {

    @Test
    fun faceButtonsFollowThePhysicalPositionOfEachVendor() {
        // Xbox 的 A/B/X/Y 分别是 下/右/左/上；PS 与 Switch 按同一物理位置换算。
        assertEquals("A", GamepadButton.A.labelFor(GamepadMode.XBOX))
        assertEquals("B", GamepadButton.B.labelFor(GamepadMode.XBOX))

        assertEquals("×", GamepadButton.A.labelFor(GamepadMode.DUALSENSE))
        assertEquals("○", GamepadButton.B.labelFor(GamepadMode.DUALSENSE))
        assertEquals("□", GamepadButton.X.labelFor(GamepadMode.DUALSENSE))
        assertEquals("△", GamepadButton.Y.labelFor(GamepadMode.DUALSENSE))

        // Switch Pro 的 A/B 与 X/Y 在物理位置上与 Xbox 相反
        assertEquals("B", GamepadButton.A.labelFor(GamepadMode.SWITCH_PRO))
        assertEquals("A", GamepadButton.B.labelFor(GamepadMode.SWITCH_PRO))
        assertEquals("Y", GamepadButton.X.labelFor(GamepadMode.SWITCH_PRO))
        assertEquals("X", GamepadButton.Y.labelFor(GamepadMode.SWITCH_PRO))
    }

    @Test
    fun shouldersAndSystemButtonsUseVendorCaptions() {
        assertEquals("L1", GamepadButton.LB.labelFor(GamepadMode.DUALSENSE))
        assertEquals("R2", GamepadButton.RT.labelFor(GamepadMode.DUALSENSE))
        assertEquals("Share", GamepadButton.BACK.labelFor(GamepadMode.DUALSENSE))
        assertEquals("PS", GamepadButton.GUIDE.labelFor(GamepadMode.DUALSENSE))

        assertEquals("ZL", GamepadButton.LT.labelFor(GamepadMode.SWITCH_PRO))
        assertEquals("Minus", GamepadButton.BACK.labelFor(GamepadMode.SWITCH_PRO))
        assertEquals("View", GamepadButton.BACK.labelFor(GamepadMode.XBOX))
    }

    @Test
    fun stepLabelsNeverLeakTheNeutralSerialName() {
        val step = GamepadStep(button = GamepadButton.A, action = GamepadAction.CLICK)

        assertEquals("手柄 A click", step.labelFor(GamepadMode.XBOX))
        assertEquals("手柄 × click", step.labelFor(GamepadMode.DUALSENSE))
        assertEquals("手柄 B click", step.labelFor(GamepadMode.SWITCH_PRO))

        // 中立标识只属于文件格式，不能出现在展示文案里
        assertTrue(!step.labelFor(GamepadMode.DUALSENSE).contains(step.button.serialName + " "))

        // 时间线用的 displayLabel 同样按类型渲染；用户自定义名称优先
        assertEquals("手柄 × click", step.displayLabel(GamepadMode.DUALSENSE))
        assertEquals("确认", step.copy(name = "确认").displayLabel(GamepadMode.DUALSENSE))
    }

    @Test
    fun nonGamepadStepsKeepTheirLabel() {
        val tap = TapStep(x = 100f, y = 200f)
        assertEquals(tap.label, tap.labelFor(GamepadMode.DUALSENSE))
        assertEquals(tap.label, tap.displayLabel(GamepadMode.DUALSENSE))
    }

    @Test
    fun stickStepLabelIsModeIndependent() {
        val stick = GamepadStep(action = GamepadAction.STICK, x = 0.5f, y = -0.25f)
        assertEquals(stick.label, stick.labelFor(GamepadMode.DUALSENSE))
        assertTrue(stick.labelFor(GamepadMode.DUALSENSE).startsWith("手柄摇杆"))
    }

    // ------------------------------------------------------ 平板键盘映射

    @Test
    fun bindableKeysCoverLettersDigitsArrowsAndFunctionRows() {
        // Android KeyEvent：A=29、0=7、方向上=19、F1=131、空格=62、回车=66
        assertTrue(GamepadKeyLabels.isBindable(29))
        assertTrue(GamepadKeyLabels.isBindable(7))
        assertTrue(GamepadKeyLabels.isBindable(19))
        assertTrue(GamepadKeyLabels.isBindable(131))
        assertTrue(GamepadKeyLabels.isBindable(62))
        assertTrue(GamepadKeyLabels.isBindable(66))
    }

    @Test
    fun systemKeysCanNeverBeBound() {
        // HOME=3、BACK=4、电源=26、最近任务=187、语音助手=219：
        // 键盘桥接即使被开启也永远不能吞掉这些键。
        assertFalse(GamepadKeyLabels.isBindable(3))
        assertFalse(GamepadKeyLabels.isBindable(4))
        assertFalse(GamepadKeyLabels.isBindable(26))
        assertFalse(GamepadKeyLabels.isBindable(187))
        assertFalse(GamepadKeyLabels.isBindable(219))
    }

    @Test
    fun labelForUsesFriendlyNamesAndFallsBackToKeyCode() {
        assertEquals("A", GamepadKeyLabels.labelFor(29))
        assertEquals("方向键 ↑", GamepadKeyLabels.labelFor(19))
        assertEquals("F12", GamepadKeyLabels.labelFor(142))
        // 未收录（也不可绑定）的键码回退为中性描述，不抛异常
        assertEquals("按键 999", GamepadKeyLabels.labelFor(999))
    }

    @Test
    fun keyCodeRoundTripsThroughSerializationAndDefaultsToNull() {
        val json = Json { ignoreUnknownKeys = true }
        val plain = GamepadButtonMapping(button = GamepadButton.A)
        assertNull(plain.keyCode)

        val bound = GamepadButtonMapping(
            button = GamepadButton.B,
            x = 540f,
            y = 1600f,
            configured = true,
            keyCode = 31,
        )
        val encoded = json.encodeToString(GamepadButtonMapping.serializer(), bound)
        assertTrue(encoded.contains("\"keyCode\":31"))

        val decoded = json.decodeFromString(GamepadButtonMapping.serializer(), encoded)
        assertEquals(31, decoded.keyCode)
        assertEquals(GamepadButton.B, decoded.button)
        assertTrue(decoded.configured)
    }

    @Test
    fun keyboardMappingSettingDefaultsOff() {
        assertFalse(AppSettings.Default.gamepadKeyboardEnabled)
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(AppSettings.serializer(), "{}")
        assertFalse(decoded.gamepadKeyboardEnabled)
    }

    @Test
    fun holdModeDefaultsOffAndOldJsonDecodesWithoutIt() {
        val json = Json { ignoreUnknownKeys = true }
        // 默认关闭
        assertFalse(GamepadButtonMapping(button = GamepadButton.A).holdMode)
        // 旧版本持久化 JSON 不含 holdMode 字段，解码后仍为 false（无损迁移）
        val legacy = json.decodeFromString(
            GamepadButtonMapping.serializer(),
            """{"button":"A","x":100.0,"y":200.0,"durationMs":60,"configured":true,"keyCode":31}""",
        )
        assertFalse(legacy.holdMode)
        assertEquals(31, legacy.keyCode)
        // 开启后随映射正常往返
        val encoded = json.encodeToString(
            GamepadButtonMapping.serializer(),
            legacy.copy(holdMode = true),
        )
        assertTrue(json.decodeFromString(GamepadButtonMapping.serializer(), encoded).holdMode)
    }

    // ------------------------------------------------ WASD 模拟左摇杆方向合成

    @Test
    fun wasdSingleKeysProduceUnitCardinalDirections() {
        // Android KeyEvent：W=51、A=29、S=47、D=32；屏幕坐标 y 向下为正。
        assertEquals(0f to -1f, GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_W)))
        assertEquals(-1f to 0f, GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_A)))
        assertEquals(0f to 1f, GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_S)))
        assertEquals(1f to 0f, GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_D)))
    }

    @Test
    fun adjacentWasdKeysProduceDiagonalDirections() {
        val upRight = GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_W, GamepadStickKeys.KEY_D))
        requireNotNull(upRight)
        assertEquals(0.70710678f, upRight.first, 1e-6f)
        assertEquals(-0.70710678f, upRight.second, 1e-6f)

        val downLeft = GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_S, GamepadStickKeys.KEY_A))
        requireNotNull(downLeft)
        assertEquals(-0.70710678f, downLeft.first, 1e-6f)
        assertEquals(0.70710678f, downLeft.second, 1e-6f)
    }

    @Test
    fun cancelingOrEmptyKeySetsYieldNoDirection() {
        // W+S 或 A+D 互相抵消；空集合与非 WASD 键同样无方向（回中）。
        assertNull(GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_W, GamepadStickKeys.KEY_S)))
        assertNull(GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_A, GamepadStickKeys.KEY_D)))
        assertNull(GamepadStickKeys.directionOf(emptySet()))
        assertNull(GamepadStickKeys.directionOf(setOf(31)))
        // 非 WASD 键被忽略：W + BACK(4) 仍等于单独按 W
        assertEquals(
            0f to -1f,
            GamepadStickKeys.directionOf(setOf(GamepadStickKeys.KEY_W, 4)),
        )
    }

    @Test
    fun stickKeyboardSettingDefaultsOff() {
        assertFalse(AppSettings.Default.gamepadKeyboardStickEnabled)
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(AppSettings.serializer(), "{}")
        assertFalse(decoded.gamepadKeyboardStickEnabled)
    }
}
