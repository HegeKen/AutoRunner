package com.autorunner.core.serialization

import com.autorunner.core.model.CoordinateSpace
import com.autorunner.core.model.DeviceInfo
import com.autorunner.core.model.DelayStep
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.GamepadAction
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.MultiTouchStep
import com.autorunner.core.model.ScriptInfo
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import com.autorunner.core.model.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptValidatorTest {

    @Test
    fun acceptsAWellFormedScript() {
        val script = ScriptModel(
            info = ScriptInfo(name = "ok", device = DeviceInfo(1080, 2400, 2.75f)),
            flow = listOf(TapStep(540f, 1200f)),
        )

        val result = ScriptValidator.validate(script)

        assertTrue(result.isValid)
        assertTrue(!result.hasWarnings, "unexpected warnings: ${result.warnings}")
    }

    @Test
    fun rejectsAnEmptyFlow() {
        val result = ScriptValidator.validate(ScriptModel())

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "empty_flow" })
    }

    @Test
    fun rejectsAnUnknownFormatVersion() {
        val result = ScriptValidator.validate(ScriptModel(version = "9.9", flow = listOf(DelayStep(10))))

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "unsupported_version" })
    }

    @Test
    fun rejectsNonPositiveDurations() {
        val result = ScriptValidator.validate(
            ScriptModel(flow = listOf(TapStep(1f, 1f, duration = 0L))),
        )

        assertFalse(result.isValid)
        assertEquals(0, result.errors.single().stepIndex)
        assertEquals("non_positive_duration", result.errors.single().code)
    }

    @Test
    fun rejectsMultiTouchWithASinglePointer() {
        val result = ScriptValidator.validate(
            ScriptModel(flow = listOf(MultiTouchStep(listOf(TouchPoint(1f, 1f))))),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "insufficient_pointers" })
    }

    @Test
    fun rejectsOutOfRangeGamepadValues() {
        val result = ScriptValidator.validate(
            ScriptModel(
                flow = listOf(
                    GamepadStep(action = GamepadAction.TRIGGER, value = 1.5f),
                    GamepadStep(action = GamepadAction.STICK, x = 2f, y = 0f),
                ),
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "invalid_analogue_value" })
        assertTrue(result.errors.any { it.code == "invalid_stick_range" })
    }

    @Test
    fun normalisedCoordinatesMustStayInsideTheUnitSquare() {
        val result = ScriptValidator.validate(
            ScriptModel(
                info = ScriptInfo(coordinateSpace = CoordinateSpace.NORMALIZED),
                flow = listOf(TapStep(1.4f, 0.5f)),
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "normalised_out_of_range" })
    }

    @Test
    fun absoluteCoordinatesOutsideTheRecordedScreenAreWarnings() {
        val result = ScriptValidator.validate(
            ScriptModel(
                info = ScriptInfo(name = "n", device = DeviceInfo(1080, 2400)),
                flow = listOf(TapStep(5000f, 100f)),
            ),
        )

        assertTrue(result.isValid, "out of bounds coordinates must not block a run")
        assertTrue(result.warnings.any { it.code == "coordinate_out_of_bounds" })
    }

    @Test
    fun missingDeviceInformationIsReported() {
        val result = ScriptValidator.validate(ScriptModel(info = ScriptInfo(name = "n"), flow = listOf(DelayStep(5))))

        assertTrue(result.warnings.any { it.code == "missing_device_info" })
    }

    @Test
    fun degenerateSwipesAreWarnedAbout() {
        val result = ScriptValidator.validate(
            ScriptModel(flow = listOf(SwipeStep(10f, 10f, 10f, 10f))),
        )

        assertTrue(result.warnings.any { it.code == "degenerate_swipe" })
    }

    @Test
    fun repeatCountInOnceModeIsAWarningOnly() {
        val result = ScriptValidator.validate(
            ScriptModel(
                execution = ExecutionConfig(mode = ExecutionMode.ONCE, repeatCount = 5),
                flow = listOf(DelayStep(5)),
            ),
        )

        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.code == "repeat_count_ignored" })
    }

    @Test
    fun negativeTimingIsAnError() {
        val result = ScriptValidator.validate(
            ScriptModel(
                execution = ExecutionConfig(intervalMs = -1, reconnectTimeoutMs = -5),
                flow = listOf(DelayStep(5, delay = -1)),
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "negative_interval" })
        assertTrue(result.errors.any { it.code == "negative_reconnect_timeout" })
        assertTrue(result.errors.any { it.code == "negative_delay" })
    }
}
