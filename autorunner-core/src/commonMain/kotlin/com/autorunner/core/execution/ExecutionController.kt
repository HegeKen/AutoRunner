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
 * 持有驱动 [AutoRunnerScriptExecutor] 的协程，并暴露悬浮面板、通知和编辑器
 * 「试运行」按钮所使用的开始 / 暂停 / 恢复 / 停止接口。
 */
class ExecutionController(
    private val executor: AutoRunnerScriptExecutor,
    private val scope: CoroutineScope,
) {

    /** 实时进度，可直接交给覆盖层渲染。 */
    val progress: StateFlow<ExecutionProgress> = executor.progress

    val state: StateFlow<ExecutionState> = executor.state

    val reports: SharedFlow<ExecutionReport> = executor.reports

    /** 当前已加载脚本的标识。 */
    var activeScriptId: String? = null
        private set

    var activeScriptName: String = ""
        private set

    private var job: Job? = null

    val isActive: Boolean get() = executor.isActive

    val isPaused: Boolean get() = state.value == ExecutionState.PAUSED

    /**
     * 启动 [script]。
     *
     * @return 已有运行在进行中时返回 `false`。
     */
    fun start(
        script: ScriptModel,
        config: ExecutionConfig = script.execution,
        scriptId: String? = null,
    ): Boolean {
        if (executor.isActive) {
            return false
        }
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

    /** 优雅停止：引擎完成当前动作后收尾退出。 */
    fun stop() = executor.stop()

    /** 无障碍服务永久消失时使用的强制停止。 */
    fun forceStop() {
        val wasActive = job?.isActive == true
        executor.stop()
        job?.cancel()
        job = null
        if (wasActive) {
            // 取消 job 意味着 execute() 永远走不到末尾的终态报告发送；
            // 代替它发布 STOPPED 报告，避免 UI 侧的收集方一直等下去。
            executor.emitStoppedReport(activeScriptId, activeScriptName)
        }
    }

    /** 清除已结束的运行，让面板回到空闲状态。 */
    fun reset() = executor.reset()
}
