package com.autorunner.core.execution

import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionState
import kotlin.test.Test
import kotlin.test.assertEquals

class ComboProgressTest {

    private fun slot(
        role: ComboSlotRole = ComboSlotRole.A,
        state: ExecutionState = ExecutionState.RUNNING,
        progress: ExecutionProgress,
        loopMs: Long = 4_000L,
        cycleMs: Long = 10_000L,
        updatedAtEpochMs: Long = 0L,
    ) = ComboSlotProgress(
        role = role,
        scriptId = role.name,
        scriptName = role.name,
        state = state,
        progress = progress,
        loopDurationMs = loopMs,
        cycleDurationMs = cycleMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    @Test
    fun cycleFractionFollowsActionAndIntervalPhases() {
        val inAction = slot(
            progress = ExecutionProgress(
                state = ExecutionState.RUNNING,
                currentActionIndex = 1,
                totalActions = 4,
            ),
        )
        // 4 秒单轮 / 4 个动作 × 2 个已完成动作 = 2 秒；周期 10 秒。
        assertEquals(0.2f, inAction.cycleFraction())

        val inInterval = slot(
            progress = ExecutionProgress(
                state = ExecutionState.RUNNING,
                intervalTotalMs = 6_000L,
                intervalElapsedMs = 3_000L,
            ),
        )
        assertEquals(0.7f, inInterval.cycleFraction())
    }

    @Test
    fun startDelayAndCompletionOverrideNormalProgress() {
        val waiting = slot(
            progress = ExecutionProgress(
                state = ExecutionState.RUNNING,
                startDelayTotalMs = 10_000L,
                startDelayElapsedMs = 5_000L,
            ),
        )
        assertEquals(0.5f, waiting.cycleFraction())

        val completed = slot(
            state = ExecutionState.COMPLETED,
            progress = ExecutionProgress(state = ExecutionState.COMPLETED),
        )
        assertEquals(1f, completed.cycleFraction())
    }

    @Test
    fun overallFractionIsTheAverageOfBothSlots() {
        val combo = ComboProgress(
            slots = listOf(
                slot(
                    role = ComboSlotRole.A,
                    progress = ExecutionProgress(
                        intervalTotalMs = 10_000L,
                        intervalElapsedMs = 10_000L,
                    ),
                ),
                slot(
                    role = ComboSlotRole.B,
                    progress = ExecutionProgress(
                        intervalTotalMs = 10_000L,
                        intervalElapsedMs = 4_000L,
                    ),
                ),
            ),
            state = ExecutionState.RUNNING,
        )
        // A = (4s + 10s) / 20s? 这里周期固定 10s，A 已填满 → 1；B = 8/10 = 0.8。
        assertEquals(0.9f, combo.overallCycleFraction())
    }

    @Test
    fun interpolationAdvancesProgressBetweenSnapshots() {
        val combo = ComboProgress(
            slots = listOf(
                slot(
                    progress = ExecutionProgress(
                        intervalTotalMs = 6_000L,
                        intervalElapsedMs = 3_000L,
                    ),
                    updatedAtEpochMs = 1_000L,
                ),
            ),
            state = ExecutionState.RUNNING,
            updatedAtEpochMs = 1_000L,
        )

        // 快照时刻：(4s + 3s) / 10s = 0.7；3 秒后补齐间隔 → 1.0。
        assertEquals(0.7f, combo.overallCycleFraction(nowEpochMs = 1_000L))
        assertEquals(1f, combo.overallCycleFraction(nowEpochMs = 4_000L))
    }

    @Test
    fun liveElapsedAdvancesOnlyWhileRunning() {
        val running = ComboProgress(
            state = ExecutionState.RUNNING,
            elapsedMs = 5_000L,
            updatedAtEpochMs = 1_000_000L,
        )
        assertEquals(8_000L, running.liveElapsedMs(nowEpochMs = 1_003_000L))

        val paused = running.copy(state = ExecutionState.PAUSED)
        assertEquals(5_000L, paused.liveElapsedMs(nowEpochMs = 1_003_000L))
    }
}
