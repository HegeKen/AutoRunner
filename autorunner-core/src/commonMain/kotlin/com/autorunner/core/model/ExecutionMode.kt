package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 已录制脚本的执行方式。
 *
 * 在 `.arscript` 文件的 `execution` 块内序列化为 `"once"` / `"repeat"`。
 */
@Serializable
enum class ExecutionMode {
    /** 整个 `flow` 恰好执行一遍后停止。 */
    @SerialName("once")
    ONCE,

    /** 整个 `flow` 执行 [ExecutionConfig.repeatCount] 遍。 */
    @SerialName("repeat")
    REPEAT;

    companion object {
        val Default: ExecutionMode = ONCE
    }
}

/**
 * 单个动作失败（例如手势执行到一半无障碍服务断开）时执行器的处理方式。
 */
@Serializable
enum class FailureStrategy {
    /** 记录失败、计一次数，然后继续下一个动作。 */
    @SerialName("skip")
    SKIP_ACTION,

    /** 中止整个运行；剩余循环不再执行。 */
    @SerialName("abort")
    ABORT_SCRIPT;

    companion object {
        val Default: FailureStrategy = ABORT_SCRIPT
    }
}

/**
 * [com.autorunner.core.execution.AutoRunnerScriptExecutor] 的生命周期。
 */
enum class ExecutionState {
    /** 没有任何运行。 */
    IDLE,

    /** 正在派发动作。 */
    RUNNING,

    /** 循环/动作执行被挂起；协程停驻在一个信号上。 */
    PAUSED,

    /** 停止请求已受理；执行器正在收尾。 */
    STOPPED,

    /** 所有请求的循环都正常完成。 */
    COMPLETED,
    ;

    val isActive: Boolean get() = this == RUNNING || this == PAUSED
}

/** 一次运行为何结束。 */
enum class ExecutionOutcome {
    COMPLETED,
    STOPPED,
    FAILED,
}
