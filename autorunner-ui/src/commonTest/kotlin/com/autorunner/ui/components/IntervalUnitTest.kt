package com.autorunner.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 循环间隔的单位吸附规则。
 *
 * 这段取整逻辑原先写在一个被 Compose 缓存复用的回调里，既不可测也容易被重构改坏；
 * 抽成 [IntervalUnit.snap] 后由这里锁住行为。
 */
class IntervalUnitTest {

    @Test
    fun roundsToNearestWholeUnit() {
        // 2.4s → 2s，2.5s → 3s
        assertEquals(2_000L, IntervalUnit.SECONDS.snap(2_400L))
        assertEquals(3_000L, IntervalUnit.SECONDS.snap(2_500L))
        // 2.33 min → 2 min
        assertEquals(120_000L, IntervalUnit.MINUTES.snap(140_000L))
        // 1.5 min 进位到 2 min
        assertEquals(120_000L, IntervalUnit.MINUTES.snap(90_000L))
    }

    @Test
    fun millisecondUnitKeepsTheExactValue() {
        assertEquals(1_400L, IntervalUnit.MILLIS.snap(1_400L))
    }

    @Test
    fun clampsToTheUnitUpperBound() {
        // 毫秒上限 5000、秒上限 300、分上限 60
        assertEquals(5_000L, IntervalUnit.MILLIS.snap(9_999L))
        assertEquals(300_000L, IntervalUnit.SECONDS.snap(400_000L))
        assertEquals(3_600_000L, IntervalUnit.MINUTES.snap(7_200_000L))
    }

    @Test
    fun nonPositiveValuesStayDisabled() {
        assertEquals(0L, IntervalUnit.SECONDS.snap(0L))
        assertEquals(0L, IntervalUnit.MINUTES.snap(-1_000L))
    }

    @Test
    fun forValuePicksTheMostReadableUnit() {
        assertEquals(IntervalUnit.SECONDS, IntervalUnit.forValue(0L))
        assertEquals(IntervalUnit.MILLIS, IntervalUnit.forValue(500L))
        assertEquals(IntervalUnit.SECONDS, IntervalUnit.forValue(2_400L))
        assertEquals(IntervalUnit.MINUTES, IntervalUnit.forValue(120_000L))
    }
}
