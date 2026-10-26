package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「组合运行」的用户配置：两段脚本各自按自己的执行方式并行循环。
 *
 * 典型用法：
 *
 * * A 每 14 分钟循环一轮；
 * * B 每 50 分钟循环一轮，并在组合启动 10 分钟后才开始第一轮。
 *
 * 首轮延迟保存在 [ComboSlotConfig.execution] 的
 * [ExecutionConfig.startDelayMs] 中；A 槽位的该值始终被归零，保证
 * 「A 先开始、B 按延迟跟进」的语义。
 *
 * 配置经 `ComboStore` 持久化到 [com.autorunner.core.storage.KeyValueStore]，
 * 只保存脚本 id 与执行参数；脚本内容仍然存放在 `.arscript` 文件里。
 */
@Serializable
data class ComboConfig(
    @SerialName("slotA") val slotA: ComboSlotConfig = ComboSlotConfig(),
    @SerialName("slotB") val slotB: ComboSlotConfig = ComboSlotConfig(),
) {

    /** 两个槽位都选择了脚本。 */
    val hasBothScripts: Boolean
        get() = slotA.scriptId.isNotBlank() && slotB.scriptId.isNotBlank()

    /** 是否选择了同一个脚本（组合运行没有意义，启动前会拒绝）。 */
    val usesSameScript: Boolean
        get() = hasBothScripts && slotA.scriptId == slotB.scriptId

    fun slot(role: ComboSlotRole): ComboSlotConfig = when (role) {
        ComboSlotRole.A -> slotA
        ComboSlotRole.B -> slotB
    }

    fun withSlot(role: ComboSlotRole, slot: ComboSlotConfig): ComboConfig = when (role) {
        ComboSlotRole.A -> copy(slotA = slot)
        ComboSlotRole.B -> copy(slotB = slot)
    }

    /**
     * 收敛到执行器能接受的取值。
     *
     * A 的首轮延迟永远是 `0`：组合的第一轮由 A 负责先开始。
     */
    fun sanitized(): ComboConfig = copy(
        slotA = slotA.copy(
            execution = slotA.execution.copy(startDelayMs = 0L).sanitized(),
        ),
        slotB = slotB.sanitized(),
    )

    companion object {
        /** 未选择脚本时的默认槽位配置：无限循环，间隔为 0。 */
        val Empty: ComboConfig = ComboConfig()
    }
}

/** 组合中单个槽位的配置。 */
@Serializable
data class ComboSlotConfig(
    /** 脚本 id（即 `.arscript` 文件基名）；空串表示尚未选择。 */
    @SerialName("scriptId") val scriptId: String = "",
    /** 该槽位使用的执行参数（循环次数 / 间隔 / 首轮延迟）。 */
    @SerialName("execution") val execution: ExecutionConfig = defaultExecution(),
) {

    val isSelected: Boolean get() = scriptId.isNotBlank()

    fun sanitized(): ComboSlotConfig = copy(execution = execution.sanitized())

    companion object {
        /** 组合运行天生是循环语义，未指定时默认无限循环。 */
        fun defaultExecution(): ExecutionConfig = ExecutionConfig(
            mode = ExecutionMode.REPEAT,
            repeatCount = ExecutionConfig.INFINITE_REPEAT,
        )
    }
}

/** 组合中的两个脚本槽位。 */
enum class ComboSlotRole(val displayName: String) {
    A("脚本 A"),
    B("脚本 B"),
}
