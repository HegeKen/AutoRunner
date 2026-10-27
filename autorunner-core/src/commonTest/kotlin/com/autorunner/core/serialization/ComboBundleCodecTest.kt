package com.autorunner.core.serialization

import com.autorunner.core.model.ComboBundle
import com.autorunner.core.model.ComboBundleScript
import com.autorunner.core.model.ComboConfig
import com.autorunner.core.model.ComboSlotConfig
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ScriptInfo
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.TapStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComboBundleCodecTest {

    private fun bundle() = ComboBundle(
        exportedAt = "2026-10-26T00:00:00Z",
        combo = ComboConfig(
            slotA = ComboSlotConfig("a", ExecutionConfig(mode = ExecutionMode.REPEAT, repeatCount = 0, intervalMs = 840_000L)),
            slotB = ComboSlotConfig(
                "b",
                ExecutionConfig(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = 0,
                    intervalMs = 3_000_000L,
                    startDelayMs = 600_000L,
                ),
            ),
        ),
        scripts = listOf(
            ComboBundleScript("a", ScriptModel(info = ScriptInfo(name = "A"), flow = listOf(TapStep(1f, 2f)))),
            ComboBundleScript("b", ScriptModel(info = ScriptInfo(name = "B"), flow = listOf(TapStep(3f, 4f)))),
        ),
    )

    @Test
    fun roundTripsComboAndScripts() {
        val decoded = assertNotNull(ComboBundleCodec.decode(ComboBundleCodec.encode(bundle())))

        assertEquals(ComboBundle.FORMAT, decoded.format)
        assertEquals(1, decoded.version)
        assertEquals("a", decoded.combo.slotA.scriptId)
        assertEquals(600_000L, decoded.combo.slotB.execution.startDelayMs)
        assertEquals(2, decoded.scripts.size)
        assertEquals("A", decoded.scripts[0].script.info.name)
        assertEquals(TapStep(1f, 2f), decoded.scripts[0].script.flow.single())
    }

    @Test
    fun rejectsForeignOrUnknownFiles() {
        assertNull(ComboBundleCodec.decode("{ not json"))
        assertNull(ComboBundleCodec.decode("""{"format":"other","version":1}"""))
        assertNull(ComboBundleCodec.decode("""{"format":"autorunner.combo","version":99}"""))
    }

    @Test
    fun suggestsAReadableFileName() {
        val name = ComboBundleCodec.fileNameFor(bundle())
        assertTrue(name.endsWith(".arcombo"))
        assertTrue(name.startsWith("A_B"))
    }
}
