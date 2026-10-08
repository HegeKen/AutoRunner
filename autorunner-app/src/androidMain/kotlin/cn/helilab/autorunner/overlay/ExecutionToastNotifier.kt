package cn.helilab.autorunner.overlay

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.ui.viewmodel.ExecutionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 运行期间的轻量提醒：开始 / 暂停 / 继续、循环进度和结束结果用短时 Toast 提示。
 *
 * 为避免 Toast 堆积阻塞，采取两条措施：
 *
 * 1. **新提示出现前取消上一条**：任何时刻最多存在一条 Toast，系统不会逐条
 *    排队阻塞；每次用新实例渲染（HyperOS 上复用实例无法即时刷新文字）；
 * 2. **循环进度限频**：两次循环提示至少间隔 [MIN_LOOP_INTERVAL_MS]；状态变化
 *    （暂停、继续）和结束报告不受限频影响，保证重要提示不丢。
 */
class ExecutionToastNotifier(
    private val context: Context,
    private val isReminderEnabled: () -> Boolean,
) {

    private var toast: Toast? = null

    private var lastState: ExecutionState? = null

    private var lastCompletedLoops = 0

    private var lastLoopToastAt = 0L

    private var latestProgress: ExecutionProgress? = null

    private var lastReport: ExecutionReport? = null

    /**
     * `true` after a start transition whose toast was deferred because the first
     * progress snapshot (carrying the script name) had not arrived yet.
     */
    private var pendingStartToast = false

    fun bind(scope: CoroutineScope, viewModel: ExecutionViewModel) {
        scope.launch { viewModel.state.collect(::onStateChanged) }
        scope.launch { viewModel.progress.collect(::onProgress) }
        scope.launch {
            viewModel.lastReport.collect { report ->
                if (report != null && report !== lastReport) {
                    lastReport = report
                    show(report.summary)
                }
            }
        }
    }

    private fun onStateChanged(state: ExecutionState) {
        val previous = lastState
        lastState = state
        // 首次收集只建立基线，不提示；状态未变也不提示。
        if (previous == null || previous == state) return
        when (state) {
            ExecutionState.RUNNING -> {
                if (previous == ExecutionState.PAUSED) {
                    pendingStartToast = false
                    show("已继续执行")
                } else {
                    val name = latestProgress?.scriptName?.takeIf { it.isNotBlank() }
                    if (name != null) {
                        pendingStartToast = false
                        show("开始执行「$name」")
                    } else {
                        // 脚本名随首个进度快照才到：先挂起，由 onProgress 补发。
                        pendingStartToast = true
                    }
                }
            }
            ExecutionState.PAUSED -> show("已暂停执行")
            // STOPPED / COMPLETED 的提示由结束报告承载，信息更完整。
            else -> Unit
        }
    }

    private fun onProgress(progress: ExecutionProgress) {
        latestProgress = progress

        if (pendingStartToast && progress.state == ExecutionState.RUNNING) {
            val name = progress.scriptName.takeIf { it.isNotBlank() }
            if (name != null) {
                pendingStartToast = false
                show("开始执行「$name」")
            }
        }

        val completed = progress.completedLoops

        // 新的一次运行（循环计数归零）：重置跟踪，不提示。
        if (completed < lastCompletedLoops) {
            lastCompletedLoops = completed
            lastLoopToastAt = 0L
            return
        }
        if (completed == lastCompletedLoops || completed == 0) {
            lastCompletedLoops = completed
            return
        }
        lastCompletedLoops = completed

        val now = SystemClock.uptimeMillis()
        if (now - lastLoopToastAt < MIN_LOOP_INTERVAL_MS) return
        lastLoopToastAt = now

        val text = when {
            progress.isInfinite || progress.totalLoops <= 0 -> "已完成 $completed 轮"
            else -> "循环 $completed/${progress.totalLoops} 完成"
        }
        show(text)
    }

    /**
     * Shows [text]; the previous toast is cancelled first so messages never
     * queue up. A brand new [Toast] is created each time: on some ROMs (实测
     * HyperOS) reusing the same instance with `setText` — even after `cancel()`
     * — does not refresh the visible window, whereas a new instance renders
     * immediately.
     */
    private fun show(text: String) {
        if (!isReminderEnabled()) return
        toast?.cancel()
        toast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    private companion object {
        /** 两次循环进度提示的最小间隔。 */
        const val MIN_LOOP_INTERVAL_MS = 5_000L
    }
}
