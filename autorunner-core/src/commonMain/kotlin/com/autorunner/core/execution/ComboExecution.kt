package com.autorunner.core.execution

import com.autorunner.core.model.ComboSlotConfig
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.ScriptModel

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
) {

    /** B 正在等待首轮开始（只在设置了首轮延迟时成立）。 */
    val isWaitingForFirstRound: Boolean
        get() = progress.startDelayTotalMs > 0L &&
            progress.startDelayElapsedMs < progress.startDelayTotalMs

    /** 正在两轮之间等待。 */
    val isInLoopInterval: Boolean
        get() = progress.intervalTotalMs > 0L &&
            progress.intervalElapsedMs < progress.intervalTotalMs

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
) {

    val isActive: Boolean get() = state.isActive

    val elapsedLabel: String get() = ExecutionProgress.formatDuration(elapsedMs)

    fun slot(role: ComboSlotRole): ComboSlotProgress? = slots.firstOrNull { it.role == role }

    /** 正在等待首轮的槽位（组合里通常是 B）；没有则为 `null`。 */
    val waitingSlot: ComboSlotProgress? get() = slots.firstOrNull { it.isWaitingForFirstRound }
}
