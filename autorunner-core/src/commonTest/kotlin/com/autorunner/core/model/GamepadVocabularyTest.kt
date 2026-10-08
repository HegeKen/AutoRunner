package com.autorunner.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
