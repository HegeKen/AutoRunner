package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a recorded script should be executed.
 *
 * Serialised as `"once"` / `"repeat"` inside the `execution` block of an
 * `.arscript` file.
 */
@Serializable
enum class ExecutionMode {
    /** Run the whole `flow` exactly once and stop. */
    @SerialName("once")
    ONCE,

    /** Run the whole `flow` [ExecutionConfig.repeatCount] times. */
    @SerialName("repeat")
    REPEAT;

    companion object {
        val Default: ExecutionMode = ONCE
    }
}

/**
 * What the executor does when a single action fails (for example because the
 * accessibility service was disconnected mid-gesture).
 */
@Serializable
enum class FailureStrategy {
    /** Log the failure, count it and continue with the next action. */
    @SerialName("skip")
    SKIP_ACTION,

    /** Abort the whole run; the remaining loops are not executed. */
    @SerialName("abort")
    ABORT_SCRIPT;

    companion object {
        val Default: FailureStrategy = ABORT_SCRIPT
    }
}

/**
 * Lifecycle of [com.autorunner.core.execution.AutoRunnerScriptExecutor].
 */
enum class ExecutionState {
    /** Nothing is running. */
    IDLE,

    /** Actions are being dispatched. */
    RUNNING,

    /** Loop/action execution is suspended; the coroutine is parked on a signal. */
    PAUSED,

    /** A stop request was honoured; the executor is winding down. */
    STOPPED,

    /** Every requested loop finished normally. */
    COMPLETED,
    ;

    val isActive: Boolean get() = this == RUNNING || this == PAUSED
}

/** Why a run ended. */
enum class ExecutionOutcome {
    COMPLETED,
    STOPPED,
    FAILED,
}
