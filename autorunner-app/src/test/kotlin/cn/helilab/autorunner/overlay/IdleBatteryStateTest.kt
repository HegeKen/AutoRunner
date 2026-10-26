package cn.helilab.autorunner.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class IdleBatteryStateTest {

    @Test
    fun labelShowsKnownPercentage() {
        assertEquals("84%", IdleBatteryState(levelPercent = 84, charging = false).label)
        assertEquals("100%", IdleBatteryState(levelPercent = 100, charging = true).label)
    }

    @Test
    fun labelFallsBackWhenLevelIsUnknown() {
        assertEquals("--%", IdleBatteryState(levelPercent = -1, charging = false).label)
    }
}
