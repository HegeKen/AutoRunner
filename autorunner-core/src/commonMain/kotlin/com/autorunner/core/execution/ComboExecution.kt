package com.autorunner.core.execution

import com.autorunner.core.model.ComboSlotConfig
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.util.currentTimeMillis
import kotlin.math.roundToLong

/**
 * 组合运行的一次启动请求：两个槽位各自的脚本与生效配置。
 *
 * 与持久化的 [com.autorunner.core.model.ComboConfig] 分开，是因为运行时需要
 * 已经加载好的 [ScriptModel]；文件层只保存脚本 id，加载动作由调用方完成。
 */
data class ComboExecution(
    val slotA: ComboScriptSlot,
    val slotB: ComboScriptSlot,
) {

    fun slot(role: ComboSlotRole): ComboScriptSlot = when (role) {
        ComboSlotRole.A -> slotA
        ComboSlotRole.B -> slotB
    }
}

/** 组合运行中的一个已加载槽位。 */
data class ComboScriptSlot(
    val role: ComboSlotRole,
    val scriptId: String?,
    val script: ScriptModel,
    val config: ExecutionConfig,
) {
    val scriptName: String get() = script.displayName()
}

/**
 * 把持久化的槽位配置与已加载脚本合成为运行时槽位。
 *
 * A 槽位的首轮延迟强制为 `0`，与 [com.autorunner.core.model.ComboConfig.sanitized]
 * 保持一致；这样即使外部写入了非法配置，也不会破坏「A 先开始」的语义。
 */
fun ComboSlotConfig.resolve(
    role: ComboSlotRole,
    script: ScriptModel,
): ComboScriptSlot {
    val effective = execution.sanitized().let {
        if (role == ComboSlotRole.A) it.copy(startDelayMs = 0L) else it
    }
    return ComboScriptSlot(
        role = role,
        scriptId = scriptId.ifBlank { null },
        script = script,
        config = effective,
    )
}

/** 组合运行中单个槽位的实时快照。 */
data class ComboSlotProgress(
    val role: ComboSlotRole,
    val scriptId: String?,
    val scriptName: String,
    val state: ExecutionState,
    val progress: ExecutionProgress,
    /** 单轮动作 + 动作间延迟的估算时长（毫秒）。 */
    val loopDurationMs: Long = 0L,
    /** 一个完整循环的估算时长：单轮时长 + 循环间隔 + 恢复延迟。 */
    val cycleDurationMs: Long = 0L,
    /** 该槽位最近一次进度更新的挂钟时间；用于插值。 */
    val updatedAtEpochMs: Long = 0L,
) {

    /** B 正在等待首轮开始（只在设置了首轮延迟时成立）。 */
    val isWaitingForFirstRound: Boolean
        get() = progress.startDelayTotalMs > 0L &&
            progress.startDelayElapsedMs < progress.startDelayTotalMs

    /** 正在两轮之间等待。 */
    val isInLoopInterval: Boolean
        get() = progress.intervalTotalMs > 0L &&
            progress.intervalElapsedMs < progress.intervalTotalMs

    /**
     * 当前这一轮的时间进度（0.0..1.0），供超级岛 / 组合页进度条使用。
     *
     * 首轮等待阶段按「已等待 / 总等待」计算；进入第一轮后按单轮动作估算时长
     * 与循环间隔推进，每轮重新从 0 开始。动作阶段没有逐动作耗时，按
     * `已完成动作 / 总动作` 分摊单轮时长，属于尽力估算。
     */
    fun cycleFraction(extraElapsedMs: Long = 0L): Float {
        if (state == ExecutionState.COMPLETED) return 1f
        val extra = extraElapsedMs.coerceAtLeast(0L)
        val delayTotal = progress.startDelayTotalMs
        if (delayTotal > 0L && progress.startDelayElapsedMs < delayTotal) {
            val elapsed = (progress.startDelayElapsedMs + extra).coerceAtMost(delayTotal)
            return (elapsed.toFloat() / delayTotal).coerceIn(0f, 1f)
        }
        if (cycleDurationMs <= 0L) return 0f
        val elapsed = when {
            progress.intervalTotalMs > 0L -> {
                val intervalElapsed = (progress.intervalElapsedMs + extra)
                    .coerceAtMost(progress.intervalTotalMs)
                loopDurationMs + intervalElapsed
            }

            progress.totalActions > 0 && progress.currentActionIndex >= 0 ->
                loopDurationMs * (progress.currentActionIndex + 1) / progress.totalActions

            else -> 0L
        }
        return (elapsed.toFloat() / cycleDurationMs).coerceIn(0f, 1f)
    }

    /** 按当前挂钟时间计算循环进度：把两次快照之间的时长补进等待阶段。 */
    fun cycleFractionAt(nowEpochMs: Long): Float {
        val extra = if (state == ExecutionState.RUNNING && updatedAtEpochMs > 0L) {
            (nowEpochMs - updatedAtEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return cycleFraction(extra)
    }

    /**
     * 距当前循环结束（下一轮开始）的估算剩余时长（毫秒）。
     *
     * 首轮等待阶段返回距第一轮开始的剩余时间；其余阶段是 [cycleFractionAt]
     * 的反面——动作阶段按单轮估算时长的剩余部分计算，并包含循环间隔。
     * 已结束 / 已停止返回 `0`。
     */
    fun remainingCycleMs(nowEpochMs: Long = currentTimeMillis()): Long {
        if (state == ExecutionState.COMPLETED || state == ExecutionState.STOPPED) return 0L
        val extra = if (state == ExecutionState.RUNNING && updatedAtEpochMs > 0L) {
            (nowEpochMs - updatedAtEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val delayTotal = progress.startDelayTotalMs
        if (delayTotal > 0L && progress.startDelayElapsedMs < delayTotal) {
            return (delayTotal - progress.startDelayElapsedMs - extra).coerceAtLeast(0L)
        }
        if (cycleDurationMs <= 0L) return 0L
        val remaining = cycleDurationMs * (1f - cycleFraction(extra))
        return remaining.roundToLong().coerceAtLeast(0L)
    }

    /** 覆盖层与组合页使用的单行状态文案。 */
    fun describeState(): String = when {
        state == ExecutionState.IDLE -> "尚未开始"
        state == ExecutionState.COMPLETED -> "已完成"
        state == ExecutionState.STOPPED -> "已停止"
        state == ExecutionState.PAUSED -> "已暂停"
        isWaitingForFirstRound -> "等待首轮 ${progress.startDelayLabel.orEmpty()}"
        isInLoopInterval -> "循环间隔 ${progress.intervalLabel.orEmpty()}"
        progress.currentActionIndex >= 0 ->
            "动作 ${progress.currentActionIndex + 1}/${progress.totalActions}"

        else -> "运行中"
    }
}

/** 组合运行的整体快照，供组合页面、悬浮面板与通知使用。 */
data class ComboProgress(
    val slots: List<ComboSlotProgress> = emptyList(),
    val state: ExecutionState = ExecutionState.IDLE,
    val elapsedMs: Long = 0L,
    /** 最近一次聚合快照的挂钟时间；用于两次进度更新之间平滑推进运行时长。 */
    val updatedAtEpochMs: Long = 0L,
) {

    val isActive: Boolean get() = state.isActive

    val elapsedLabel: String get() = ExecutionProgress.formatDuration(elapsedMs)

    /**
     * 两次进度快照之间的实时运行时长。
     *
     * 执行器只会在动作边界与间隔 tick 上报进度；超级岛需要「当前运行时间」
     * 持续走动，因此在运行中使用「上次快照 elapsed + 距上次快照的墙钟差」。
     * 暂停 / 已结束时不再累加，避免把暂停时间算进去。
     */
    fun liveElapsedMs(nowEpochMs: Long = currentTimeMillis()): Long {
        if (state != ExecutionState.RUNNING || updatedAtEpochMs <= 0L) return elapsedMs
        return elapsedMs + (nowEpochMs - updatedAtEpochMs).coerceAtLeast(0L)
    }

    /**
     * 两个槽位当前循环进度的平均值（0.0..1.0）。
     *
     * [nowEpochMs] 晚于 [updatedAtEpochMs] 时，把两次进度快照之间的时长补进
     * 正在等待的间隔 / 首轮延迟，让超级岛进度条在长时间等待中也能平滑推进。
     */
    fun overallCycleFraction(nowEpochMs: Long = updatedAtEpochMs): Float {
        if (slots.isEmpty()) return 0f
        return (slots.sumOf { it.cycleFractionAt(nowEpochMs).toDouble() } / slots.size)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun slot(role: ComboSlotRole): ComboSlotProgress? = slots.firstOrNull { it.role == role }

    /** 正在等待首轮的槽位（组合里通常是 B）；没有则为 `null`。 */
    val waitingSlot: ComboSlotProgress? get() = slots.firstOrNull { it.isWaitingForFirstRound }
}
