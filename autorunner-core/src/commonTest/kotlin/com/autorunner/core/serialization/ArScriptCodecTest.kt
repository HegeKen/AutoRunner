package com.autorunner.core.serialization

import com.autorunner.core.model.CoordinateSpace
import com.autorunner.core.model.DelayStep
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.GamepadAction
import com.autorunner.core.model.GamepadButton
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.MultiTouchStep
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import com.autorunner.core.model.TouchPoint
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArScriptCodecTest {

    private val codec = ArScriptCodec()

    /** 设计规格 §6.2 中展示的原始文档。 */
    private val specificationDocument = """
        {
          "version": "1.0",
          "info": {
            "name": "示例脚本",
            "description": "录制于 2026-01-15",
            "device": { "width": 1080, "height": 2400, "density": 2.75 },
            "createdAt": "2026-01-15T10:30:00Z"
          },
          "execution": {
            "mode": "repeat",
            "repeatCount": 10,
            "intervalMs": 2000
          },
          "flow": [
            { "type": "tap", "x": 540, "y": 1200, "duration": 50, "delay": 500 },
            { "type": "swipe", "fromX": 540, "fromY": 1800, "toX": 540, "toY": 600,
              "duration": 300, "delay": 200 },
            { "type": "longPress", "x": 540, "y": 1200, "duration": 1500, "delay": 300 },
            { "type": "gamepad", "button": "A", "action": "press", "delay": 100 }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTheDocumentedExample() {
        val script = codec.decode(specificationDocument)

        assertEquals("1.0", script.version)
        assertEquals("示例脚本", script.info.name)
        assertEquals(1080, script.info.device.width)
        assertEquals(2400, script.info.device.height)
        assertEquals(2.75f, script.info.device.density)
        assertEquals(ExecutionMode.REPEAT, script.execution.mode)
        assertEquals(10, script.execution.repeatCount)
        assertEquals(2000L, script.execution.intervalMs)
        assertEquals(4, script.flow.size)

        val tap = script.flow[0] as TapStep
        assertEquals(540f, tap.x)
        assertEquals(1200f, tap.y)
        assertEquals(50L, tap.duration)
        assertEquals(500L, tap.delay)

        val swipe = script.flow[1] as SwipeStep
        assertEquals(540f, swipe.fromX)
        assertEquals(1800f, swipe.fromY)
        assertEquals(540f, swipe.toX)
        assertEquals(600f, swipe.toY)

        val longPress = script.flow[2] as LongPressStep
        assertEquals(1500L, longPress.duration)

        val gamepad = script.flow[3] as GamepadStep
        assertEquals(GamepadButton.A, gamepad.button)
        assertEquals(GamepadAction.PRESS, gamepad.action)
    }

    @Test
    fun writesTheTypeDiscriminator() {
        val encoded = codec.encode(ScriptModel(flow = listOf(TapStep(1f, 2f))))
        assertContains(encoded, "\"type\": \"tap\"")
        assertContains(encoded, "\"flow\"")
        assertContains(encoded, "\"execution\"")
    }

    @Test
    fun roundTripsEveryActionType() {
        val script = ScriptModel(
            flow = listOf(
                TapStep(10f, 20f, 30L, 40L),
                LongPressStep(11f, 21f, 900L, 41L),
                SwipeStep(1f, 2f, 3f, 4f, 250L, 50L),
                MultiTouchStep(listOf(TouchPoint(5f, 6f), TouchPoint(7f, 8f, 12L)), 320L, 60L),
                GamepadStep(button = GamepadButton.RT, action = GamepadAction.TRIGGER, value = 0.5f, delay = 70L),
                DelayStep(duration = 1234L),
            ),
        )

        val decoded = codec.decode(codec.encode(script))

        assertEquals(script.flow.size, decoded.flow.size)
        script.flow.forEachIndexed { index, step ->
            assertEquals(step::class, decoded.flow[index]::class, "step $index changed type")
        }
        assertEquals(CoordinateSpace.ABSOLUTE, decoded.info.coordinateSpace)
    }

    @Test
    fun ignoresUnknownKeysForForwardCompatibility() {
        val payload = """
            {
              "version": "1.0",
              "futureField": { "a": 1 },
              "info": { "name": "n", "unknown": true },
              "flow": [ { "type": "tap", "x": 1, "y": 2, "futureOption": "x" } ]
            }
        """.trimIndent()

        val script = codec.decode(payload)

        assertEquals(1, script.flow.size)
        assertEquals(1f, (script.flow.single() as TapStep).x)
    }

    @Test
    fun reportsBrokenPayloads() {
        assertFailsWith<ScriptFormatException> { codec.decode("") }
        assertFailsWith<ScriptFormatException> { codec.decode("{ not json") }
        assertFailsWith<ScriptFormatException> { codec.decode("""{"flow":[{"type":"unknown"}]}""") }
    }

    @Test
    fun nonThrowingHelpersReturnNullOnFailure() {
        assertNull(codec.decodeOrNull("{ not json"))
        assertNull(codec.decodeOrNull("   "))
        assertTrue(codec.decodeResult(specificationDocument).isSuccess)
        assertTrue(codec.decodeResult("nope").isFailure)
        assertTrue(codec.looksLikeScript(specificationDocument))
        assertTrue(!codec.looksLikeScript("hello"))
    }

    @Test
    fun compactsAndPrettyPrints() {
        val script = ScriptModel(flow = listOf(TapStep(1f, 2f)))
        val pretty = codec.encode(script, formatted = true)
        val compact = codec.encode(script, formatted = false)
        assertTrue(pretty.contains("\n"), "pretty output should be multi line")
        assertTrue(!compact.contains("\n"), "compact output should be single line")
        assertEquals(codec.decode(pretty), codec.decode(compact))
    }

    @Test
    fun defaultsAreWrittenSoExportedFilesAreSelfDescribing() {
        val encoded = codec.encode(ScriptModel(flow = listOf(TapStep(1f, 2f))))
        assertContains(encoded, "\"mode\": \"once\"")
        assertContains(encoded, "\"coordinateSpace\": \"absolute\"")
        assertNotNull(codec.decode(encoded).info.device)
    }
}
