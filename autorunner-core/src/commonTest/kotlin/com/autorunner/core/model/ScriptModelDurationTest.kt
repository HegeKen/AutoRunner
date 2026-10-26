package com.autorunner.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 编辑器顶部「循环任务预计总时长」依赖的估算公式。
 *
 * 总时长 = 首轮延迟 + 单轮耗时 × 循环次数 + 循环间隔 ×（循环次数 - 1）。
 */
class ScriptModelDurationTest {

    @Test
    fun totalDurationIncludesPerLoopTimeAndIntervals() {
        val script = ScriptModel(
            flow = listOf(DelayStep(duration = 1_000L, delay = 500L)),
            execution = ExecutionConfig(
                mode = ExecutionMode.REPEAT,
                repeatCount = 3,
                intervalMs = 2_000L,
            ),
        )

        assertEquals(1_500L, script.estimatedDurationMs)
        // 3 轮 × 1.5s + 2 次间隔 × 2s
        assertEquals(8_500L, script.estimatedTotalDurationMs())
    }

    @Test
    fun swipeDurationAndDelayAreBothCounted() {
        val script = ScriptModel(
            flow = listOf(SwipeStep(1_000f, 1_300f, 200f, 1_300f, duration = 400L, delay = 300L)),
            execution = ExecutionConfig(
                mode = ExecutionMode.REPEAT,
                repeatCount = 2,
                intervalMs = 1_500L,
            ),
        )

        assertEquals(700L, script.estimatedDurationMs)
        assertEquals(2_900L, script.estimatedTotalDurationMs())
    }

    @Test
    fun restoreDelayBetweenLoopsIsIncluded() {
        val script = ScriptModel(
            flow = listOf(DelayStep(duration = 1_000L)),
            execution = ExecutionConfig(
                mode = ExecutionMode.REPEAT,
                repeatCount = 2,
                intervalMs = 2_000L,
                restoreDelayMs = 500L,
            ),
        )

        // 2 轮 × 1s + 1 次（2s 间隔 + 0.5s 恢复延迟）
        assertEquals(4_500L, script.estimatedTotalDurationMs())
    }

    @Test
    fun startDelayAndSingleExecutionAreIncluded() {
        val script = ScriptModel(
            flow = listOf(DelayStep(duration = 1_000L)),
            execution = ExecutionConfig(
                mode = ExecutionMode.ONCE,
                startDelayMs = 5_000L,
            ),
        )

        assertEquals(6_000L, script.estimatedTotalDurationMs())
    }

    @Test
    fun infiniteLoopsHaveNoFiniteTotalDuration() {
        val script = ScriptModel(
            flow = listOf(DelayStep(duration = 1_000L)),
            execution = ExecutionConfig(
                mode = ExecutionMode.REPEAT,
                repeatCount = ExecutionConfig.INFINITE_REPEAT,
                intervalMs = 10_000L,
            ),
        )

        assertEquals(Long.MAX_VALUE, script.estimatedTotalDurationMs())
    }
}
