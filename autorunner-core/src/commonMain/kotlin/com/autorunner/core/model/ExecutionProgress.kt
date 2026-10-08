package com.autorunner.core.model

/**
 * Snapshot of a running (or finished) execution, published to the floating
 * panel and the foreground notification.
 */
data class ExecutionProgress(
    val state: ExecutionState = ExecutionState.IDLE,
    /** Script being executed, if any. */
    val scriptId: String? = null,
    val scriptName: String = "",
    /** Loops already finished. */
    val completedLoops: Int = 0,
    /**
     * Loops requested by the user. [ExecutionConfig.INFINITE_LOOPS] while the
     * script loops forever.
     */
    val totalLoops: Int = 0,
    /** Zero based index of the action currently being dispatched, `-1` if idle. */
    val currentActionIndex: Int = -1,
    val totalActions: Int = 0,
    val currentActionLabel: String = "",
    /** Wall clock time spent in the current run. */
    val elapsedMs: Long = 0L,
    /** Actions that failed and were skipped. */
    val skippedActions: Int = 0,
    /** Optional status line (failure reason, reconnection notice, …). */
    val message: String? = null,
) {
    val isInfinite: Boolean get() = totalLoops >= ExecutionConfig.INFINITE_LOOPS

    /** `0.0..1.0` completion of the whole run, or `null` when it never ends. */
    val loopFraction: Float?
        get() = when {
            totalLoops <= 0 -> null
            isInfinite -> null
            else -> (completedLoops.toFloat() / totalLoops).coerceIn(0f, 1f)
        }

    /** `0.0..1.0` completion of the current loop, or `null` when unknown. */
    val actionFraction: Float?
        get() = when {
            totalActions <= 0 || currentActionIndex < 0 -> null
            else -> ((currentActionIndex + 1).toFloat() / totalActions).coerceIn(0f, 1f)
        }

    /** `3 / 10` style loop counter for the overlay. */
    val loopLabel: String
        get() = when {
            totalLoops <= 0 -> "—"
            isInfinite -> "$completedLoops / ∞"
            else -> "$completedLoops / $totalLoops"
        }

    val elapsedLabel: String get() = formatDuration(elapsedMs)

    fun formattedState(): String = when (state) {
        ExecutionState.IDLE -> "空闲"
        ExecutionState.RUNNING -> "运行中"
        ExecutionState.PAUSED -> "已暂停"
        ExecutionState.STOPPED -> "已停止"
        ExecutionState.COMPLETED -> "已完成"
    }

    companion object {
        fun idle(scriptId: String? = null, scriptName: String = ""): ExecutionProgress =
            ExecutionProgress(state = ExecutionState.IDLE, scriptId = scriptId, scriptName = scriptName)

        fun formatDuration(millis: Long): String {
            if (millis <= 0L) return "00:00"
            val totalSeconds = millis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "${hours.pad()}:${minutes.pad()}:${seconds.pad()}"
            } else {
                "${minutes.pad()}:${seconds.pad()}"
            }
        }

        private fun Long.pad(): String = if (this < 10) "0$this" else this.toString()
    }
}

/** Final report produced when a run ends. */
data class ExecutionReport(
    val scriptId: String?,
    val scriptName: String,
    val outcome: ExecutionOutcome,
    val completedLoops: Int,
    val totalLoops: Int,
    val executedActions: Int,
    val skippedActions: Int,
    val elapsedMs: Long,
    val failureMessage: String? = null,
) {
    val summary: String
        get() = buildString {
            append(
                when (outcome) {
                    ExecutionOutcome.COMPLETED -> "执行完成"
                    ExecutionOutcome.STOPPED -> "已手动停止"
                    ExecutionOutcome.FAILED -> "执行失败"
                },
            )
            append(" · ")
            append(completedLoops)
            if (totalLoops in 1 until ExecutionConfig.INFINITE_LOOPS) {
                append('/')
                append(totalLoops)
            }
            append(" 轮 · 耗时 ")
            append(ExecutionProgress.formatDuration(elapsedMs))
            if (skippedActions > 0) {
                append(" · 跳过 ")
                append(skippedActions)
                append(" 个动作")
            }
            failureMessage?.let {
                append(" · ")
                append(it)
            }
        }
}
