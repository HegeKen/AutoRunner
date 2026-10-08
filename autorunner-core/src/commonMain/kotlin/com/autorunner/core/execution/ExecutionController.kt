package com.autorunner.core.execution

import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.ScriptModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the coroutine that drives [AutoRunnerScriptExecutor] and exposes the
 * start / pause / resume / stop surface used by the floating panel, the
 * notification and the editor's "试运行" button.
 */
class ExecutionController(
    private val executor: AutoRunnerScriptExecutor,
    private val scope: CoroutineScope,
) {

    /** Live progress, ready to be rendered by the overlay. */
    val progress: StateFlow<ExecutionProgress> = executor.progress

    val state: StateFlow<ExecutionState> = executor.state

    val reports: SharedFlow<ExecutionReport> = executor.reports

    /** Identifier of the script that is currently loaded. */
    var activeScriptId: String? = null
        private set

    var activeScriptName: String = ""
        private set

    private var job: Job? = null

    val isActive: Boolean get() = executor.isActive

    val isPaused: Boolean get() = state.value == ExecutionState.PAUSED

    /**
     * Starts [script].
     *
     * @return `false` when a run is already in flight.
     */
    fun start(
        script: ScriptModel,
        config: ExecutionConfig = script.execution,
        scriptId: String? = null,
    ): Boolean {
        if (executor.isActive) return false
        executor.reset()
        activeScriptId = scriptId
        activeScriptName = script.displayName()
        job = scope.launch {
            executor.execute(script = script, config = config, scriptId = scriptId)
        }
        return true
    }

    fun pause() = executor.pause()

    fun resume() = executor.resume()

    fun togglePause() = executor.togglePause()

    /** Graceful stop: the engine finishes the current action, then unwinds. */
    fun stop() = executor.stop()

    /** Hard stop used when the accessibility service goes away permanently. */
    fun forceStop() {
        val wasActive = job?.isActive == true
        executor.stop()
        job?.cancel()
        job = null
        if (wasActive) {
            // Cancelling the job means execute() never reaches its terminal
            // report emission; publish the STOPPED report on its behalf so UI
            // collectors are not left waiting.
            executor.emitStoppedReport(activeScriptId, activeScriptName)
        }
    }

    /** Clears a finished run so the panel returns to its idle state. */
    fun reset() = executor.reset()
}
