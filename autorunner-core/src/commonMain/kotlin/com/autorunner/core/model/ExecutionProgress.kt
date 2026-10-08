package com.autorunner.core.model

/**
 * 正在运行（或已结束）执行的快照，发布到悬浮面板与前台通知。
 */
data class ExecutionProgress(
    val state: ExecutionState = ExecutionState.IDLE,
    /** 正在执行的脚本（如有）。 */
    val scriptId: String? = null,
    val scriptName: String = "",
    /** 已完成的循环数。 */
    val completedLoops: Int = 0,
    /**
     * 用户请求的循环数。脚本无限循环时为 [ExecutionConfig.INFINITE_LOOPS]。
     */
    val totalLoops: Int = 0,
    /** 当前正在派发的动作的从 0 开始的序号；空闲时为 `-1`。 */
    val currentActionIndex: Int = -1,
    val totalActions: Int = 0,
    val currentActionLabel: String = "",
    /** 本次运行已消耗的挂钟时间。 */
    val elapsedMs: Long = 0L,
    /** 失败并被跳过的动作数。 */
    val skippedActions: Int = 0,
    /** 可选的状态行（失败原因、重连提示……）。 */
    val message: String? = null,
    /** 当前循环间隔的总时长（毫秒）；`0` 表示此刻不处于间隔等待中。 */
    val intervalTotalMs: Long = 0L,
    /** 本段循环间隔已等待的时长（毫秒）。 */
    val intervalElapsedMs: Long = 0L,
) {
    val isInfinite: Boolean get() = totalLoops >= ExecutionConfig.INFINITE_LOOPS

    /** 整个运行的完成度 `0.0..1.0`；永不停止时为 `null`。 */
    val loopFraction: Float?
        get() = when {
            totalLoops <= 0 -> null
            isInfinite -> null
            else -> (completedLoops.toFloat() / totalLoops).coerceIn(0f, 1f)
        }

    /** 当前循环的完成度 `0.0..1.0`；未知时为 `null`。 */
    val actionFraction: Float?
        get() = when {
            totalActions <= 0 || currentActionIndex < 0 -> null
            else -> ((currentActionIndex + 1).toFloat() / totalActions).coerceIn(0f, 1f)
        }

    /** 供覆盖层显示的 `3 / 10` 形式循环计数。 */
    val loopLabel: String
        get() = when {
            totalLoops <= 0 -> "—"
            isInfinite -> "$completedLoops / ∞"
            else -> "$completedLoops / $totalLoops"
        }

    val elapsedLabel: String get() = formatDuration(elapsedMs)

    /**
     * `7/14 分钟` 风格的循环间隔进度（已过时长/总时长），不在间隔等待中时为 `null`。
     * 间隔不足 1 分钟时按秒显示，避免出现 `0/0 分钟`。
     */
    val intervalLabel: String?
        get() = when {
            intervalTotalMs <= 0L -> null
            intervalTotalMs >= 60_000L ->
                "${intervalElapsedMs / 60_000}/${intervalTotalMs / 60_000} 分钟"

            else -> "${intervalElapsedMs / 1_000}/${intervalTotalMs / 1_000} 秒"
        }

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

/** 运行结束时产出的最终报告。 */
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
