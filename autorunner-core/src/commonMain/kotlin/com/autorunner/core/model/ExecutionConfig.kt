package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `execution` block of an `.arscript` file.
 *
 * ```json
 * "execution": { "mode": "repeat", "repeatCount": 10, "intervalMs": 2000 }
 * ```
 */
@Serializable
data class ExecutionConfig(
    @SerialName("mode") val mode: ExecutionMode = ExecutionMode.ONCE,
    /** Number of loops; only meaningful for [ExecutionMode.REPEAT]. `0` = infinite. */
    @SerialName("repeatCount") val repeatCount: Int = 1,
    /** Idle time between two complete passes over `flow`, in milliseconds. */
    @SerialName("intervalMs") val intervalMs: Long = 0L,
    /** Behaviour when a single action fails. */
    @SerialName("failureStrategy") val failureStrategy: FailureStrategy = FailureStrategy.ABORT_SCRIPT,
    /** Milliseconds to wait for the accessibility service to come back before giving up. */
    @SerialName("reconnectTimeoutMs") val reconnectTimeoutMs: Long = 15_000L,
    /** Milliseconds to pause after each full loop when the screen must settle. */
    @SerialName("restoreDelayMs") val restoreDelayMs: Long = 0L,
) {

    /** `true` when [repeatCount] is `0`, i.e. the script loops until stopped. */
    val isInfinite: Boolean get() = mode == ExecutionMode.REPEAT && repeatCount == INFINITE_REPEAT

    /**
     * Number of passes the executor will perform, or [INFINITE_LOOPS] for an
     * endless run.
     */
    val totalLoops: Int
        get() = when (mode) {
            ExecutionMode.ONCE -> 1
            ExecutionMode.REPEAT -> if (repeatCount == INFINITE_REPEAT) INFINITE_LOOPS else repeatCount.coerceAtLeast(1)
        }

    /** Clamps every field into a value the executor can honour. */
    fun sanitized(): ExecutionConfig = copy(
        repeatCount = repeatCount.coerceAtLeast(0),
        intervalMs = intervalMs.coerceAtLeast(0L),
        reconnectTimeoutMs = reconnectTimeoutMs.coerceAtLeast(0L),
        restoreDelayMs = restoreDelayMs.coerceAtLeast(0L),
    )

    /** A short localised description used by the UI. */
    fun describe(): String = when {
        mode == ExecutionMode.ONCE -> "单次执行"
        isInfinite -> "无限循环 · 间隔 ${intervalMs}ms"
        else -> "重复 $repeatCount 次 · 间隔 ${intervalMs}ms"
    }

    companion object {
        /** `repeatCount` value that means "loop forever". */
        const val INFINITE_REPEAT = 0

        /** Sentinel used by [totalLoops] for endless runs. */
        const val INFINITE_LOOPS = Int.MAX_VALUE

        val Single: ExecutionConfig = ExecutionConfig(mode = ExecutionMode.ONCE)

        fun repeat(times: Int, intervalMs: Long = 0L): ExecutionConfig = ExecutionConfig(
            mode = ExecutionMode.REPEAT,
            repeatCount = times.coerceAtLeast(0),
            intervalMs = intervalMs.coerceAtLeast(0L),
        )
    }
}
