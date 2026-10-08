package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `.arscript` 文件的 `execution` 块。
 *
 * ```json
 * "execution": { "mode": "repeat", "repeatCount": 10, "intervalMs": 2000 }
 * ```
 */
@Serializable
data class ExecutionConfig(
    @SerialName("mode") val mode: ExecutionMode = ExecutionMode.ONCE,
    /** 循环次数；仅对 [ExecutionMode.REPEAT] 有意义。`0` = 无限。 */
    @SerialName("repeatCount") val repeatCount: Int = 1,
    /** 完整遍历一遍 `flow` 之间的空闲时间，单位毫秒。 */
    @SerialName("intervalMs") val intervalMs: Long = 0L,
    /** 单个动作失败时的行为。 */
    @SerialName("failureStrategy") val failureStrategy: FailureStrategy = FailureStrategy.ABORT_SCRIPT,
    /** 放弃前等待无障碍服务恢复的毫秒数。 */
    @SerialName("reconnectTimeoutMs") val reconnectTimeoutMs: Long = 15_000L,
    /** 屏幕需要稳定时，每个完整循环后的暂停毫秒数。 */
    @SerialName("restoreDelayMs") val restoreDelayMs: Long = 0L,
) {

    /** [repeatCount] 为 `0` 时为 `true`，即脚本循环到被停止为止。 */
    val isInfinite: Boolean get() = mode == ExecutionMode.REPEAT && repeatCount == INFINITE_REPEAT

    /**
     * 执行器将执行的遍数；无尽运行时为 [INFINITE_LOOPS]。
     */
    val totalLoops: Int
        get() = when (mode) {
            ExecutionMode.ONCE -> 1
            ExecutionMode.REPEAT -> if (repeatCount == INFINITE_REPEAT) INFINITE_LOOPS else repeatCount.coerceAtLeast(1)
        }

    /** 把每个字段收敛到执行器能接受的取值。 */
    fun sanitized(): ExecutionConfig = copy(
        repeatCount = repeatCount.coerceAtLeast(0),
        intervalMs = intervalMs.coerceAtLeast(0L),
        reconnectTimeoutMs = reconnectTimeoutMs.coerceAtLeast(0L),
        restoreDelayMs = restoreDelayMs.coerceAtLeast(0L),
    )

    /** 界面使用的简短本地化描述。 */
    fun describe(): String = when {
        mode == ExecutionMode.ONCE -> "单次执行"
        isInfinite -> "无限循环 · 间隔 ${intervalMs}ms"
        else -> "重复 $repeatCount 次 · 间隔 ${intervalMs}ms"
    }

    companion object {
        /** 表示「永久循环」的 `repeatCount` 取值。 */
        const val INFINITE_REPEAT = 0

        /** [totalLoops] 用于无尽运行的哨兵值。 */
        const val INFINITE_LOOPS = Int.MAX_VALUE

        val Single: ExecutionConfig = ExecutionConfig(mode = ExecutionMode.ONCE)

        fun repeat(times: Int, intervalMs: Long = 0L): ExecutionConfig = ExecutionConfig(
            mode = ExecutionMode.REPEAT,
            repeatCount = times.coerceAtLeast(0),
            intervalMs = intervalMs.coerceAtLeast(0L),
        )
    }
}
