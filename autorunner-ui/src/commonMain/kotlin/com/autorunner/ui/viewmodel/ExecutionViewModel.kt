package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.execution.ExecutionController
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.InputMode
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.script.ScriptRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder that drives [ExecutionController] from the UI and the floating
 * panel (§6.3, §6.4.3).
 *
 * The overlay service creates its own instance from the same [AppContainer], so
 * the in-app screen and the floating panel always show identical state.
 */
class ExecutionViewModel(
    private val container: AppContainer,
) {

    private val controller: ExecutionController = container.executionController
    private val repository = container.scriptRepository
    private val scope = container.scope

    val progress: StateFlow<ExecutionProgress> = controller.progress

    val state: StateFlow<ExecutionState> = controller.state

    val scripts: StateFlow<List<ScriptRecord>> = repository.scripts

    private val _selectedScriptId = MutableStateFlow<String?>(null)

    val selectedScriptId: StateFlow<String?> = _selectedScriptId.asStateFlow()

    private val _mode = MutableStateFlow(ExecutionMode.ONCE)

    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    private val _repeatCount = MutableStateFlow(1)

    val repeatCount: StateFlow<Int> = _repeatCount.asStateFlow()

    private val _intervalMs = MutableStateFlow(0L)

    val intervalMs: StateFlow<Long> = _intervalMs.asStateFlow()

    private val _lastReport = MutableStateFlow<ExecutionReport?>(null)

    val lastReport: StateFlow<ExecutionReport?> = _lastReport.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    val isRunning: Boolean get() = state.value.isActive

    val accessibilityConnected: Boolean get() = container.accessibilityController.isConnected

    /** Root 桥（Magisk/KSU 模块）是否就绪。 */
    val rootInputAvailable: Boolean get() = container.rootInputBackend?.isAvailable == true

    /** 当前设置的输入方式。 */
    val inputMode: InputMode get() = container.settingsRepository.current.inputMode

    init {
        refreshScripts()
        applySettingsDefaults()
        scope.launch {
            controller.reports.collect { report ->
                _lastReport.value = report
                _message.value = report.summary
            }
        }
    }

    fun refreshScripts() {
        scope.launch { repository.refresh() }
    }

    /** Copies the user's preferred defaults into the panel controls. */
    fun applySettingsDefaults() {
        val defaults = container.settingsRepository.current.defaultExecution
        _mode.value = defaults.mode
        _repeatCount.value = defaults.repeatCount
        _intervalMs.value = defaults.intervalMs
    }

    fun selectScript(id: String?) {
        _selectedScriptId.value = id
        // 切换脚本意味着放弃上一次「已就绪待运行」的悬浮球状态。
        _armed.value = false
        if (id == null) return
        scope.launch {
            val record = repository.load(id) ?: return@launch
            adoptExecution(record)
        }
    }

    /**
     * Copies the `execution` block stored inside the script into the panel controls
     * (§6.2).
     *
     * Called whenever the *active script changes*, and notably from [start]: the
     * library's ▶ button starts a script without going through the panel, so
     * without this a script saved as "repeat 20×" would silently run once with the
     * global defaults.
     */
    private fun adoptExecution(record: ScriptRecord) {
        _mode.value = record.script.execution.mode
        _repeatCount.value = record.script.execution.repeatCount
        _intervalMs.value = record.script.execution.intervalMs
    }

    fun setMode(mode: ExecutionMode) {
        _mode.value = mode
    }

    fun setRepeatCount(count: Int) {
        _repeatCount.value = count.coerceAtLeast(0)
        if (count != 1 && _mode.value == ExecutionMode.ONCE) {
            _mode.value = ExecutionMode.REPEAT
        }
    }

    fun setIntervalMs(intervalMs: Long) {
        _intervalMs.value = intervalMs.coerceAtLeast(0)
    }

    /** Builds the effective configuration from the panel controls. */
    fun buildConfig(): ExecutionConfig = ExecutionConfig(
        mode = _mode.value,
        repeatCount = _repeatCount.value,
        intervalMs = _intervalMs.value,
        failureStrategy = container.settingsRepository.current.failureStrategy,
    ).sanitized()

    /** Starts the selected script. */
    /** 「已就绪待运行」：悬浮窗已显示，等用户在悬浮窗上点运行。 */
    private val _armed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val armed: kotlinx.coroutines.flow.StateFlow<Boolean> = _armed.asStateFlow()

    /**
     * 列表点「运行」：不立刻执行，而是显示悬浮窗并回到桌面，让用户先打开目标页面，
     * 再由用户在悬浮窗上点「运行」真正开始（图标随运行状态变成停止）。
     */
    fun armAndGoHome(scriptId: String) {
        if (controller.isActive) {
            _message.value = "已有任务正在执行"
            return
        }
        scope.launch {
            val record = repository.load(scriptId) ?: run {
                _message.value = "脚本不存在"
                return@launch
            }
            if (record.script.flow.isEmpty()) {
                _message.value = "「${record.name}」不包含任何动作"
                return@launch
            }
            if (!accessibilityConnected && !rootInputAvailable) {
                _message.value = "无障碍服务未连接，无法执行"
                return@launch
            }
            if (_selectedScriptId.value != scriptId) {
                _selectedScriptId.value = scriptId
                adoptExecution(record)
            }
            val overlay = container.overlayManager
            if (!overlay.isPermissionGranted()) {
                _message.value = "请先授予悬浮窗权限，运行时会用它显示开始/停止按钮"
                return@launch
            }
            // 以悬浮球形式显示并把脚本 ID 交给悬浮服务：用户单击悬浮球即开始。
            if (!overlay.armExecution(scriptId)) {
                _message.value = "无法显示悬浮控制，请检查悬浮窗权限"
                return@launch
            }
            _armed.value = true
            overlay.goHome()
        }
    }

    fun start(scriptId: String? = _selectedScriptId.value) {
        _armed.value = false
        if (controller.isActive) {
            _message.value = "已有任务正在执行"
            return
        }
        val id = scriptId ?: run {
            _message.value = "请先选择要运行的脚本"
            return
        }
        scope.launch {
            val record = repository.load(id)
            if (record == null) {
                _message.value = "脚本不存在"
                return@launch
            }
            if (record.script.flow.isEmpty()) {
                _message.value = "「${record.name}」不包含任何动作"
                return@launch
            }
            // Root 注入可用时不再强制要求无障碍服务（设置里可切换输入方式）。
            if (!accessibilityConnected && !rootInputAvailable) {
                _message.value = if (inputMode == InputMode.ROOT) {
                    "Root 注入未就绪：请在 root 管理器中允许本应用，或刷入 AutoRunner Root Bridge 模块"
                } else {
                    "无障碍服务未连接，无法执行"
                }
                return@launch
            }
            // Switching to another script adopts *its* stored execution block; a
            // script that is already selected keeps whatever the panel shows, so an
            // explicit tweak is never silently overwritten.
            if (_selectedScriptId.value != id) {
                _selectedScriptId.value = id
                adoptExecution(record)
            }
            val config = buildConfig()
            val started = controller.start(record.script, config, scriptId = id)
            // Deliberately silent on success: a run that starts must not interrupt
            // the user with a dialog. The floating banner and the progress row show
            // what is running; failures below still surface a message.
            if (!started) {
                _message.value = "启动失败：已有任务正在执行"
            }
        }
    }

    /** Quick action used by the floating ball: run the first available script. */
    fun startFirstAvailable() {
        val id = _selectedScriptId.value ?: scripts.value.firstOrNull()?.id
        start(id)
    }

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun togglePause() = controller.togglePause()

    fun stop() = controller.stop()

    fun reset() {
        controller.reset()
        _lastReport.value = null
        // 同步清空结果消息，否则横幅的显示条件（message != null）仍成立。
        _message.value = null
        // 复位后不应再保留「已就绪待运行」状态，否则悬浮球会停在旧的待运行脚本上。
        _armed.value = false
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** `true` when the panel should offer a "start" affordance. */
    fun canStart(): Boolean = !isRunning && scripts.value.isNotEmpty()

    /** Serialises the selected script for a share/export action. */
    fun selectedScript(): ScriptModel? = _selectedScriptId.value?.let { id ->
        scripts.value.firstOrNull { it.id == id }?.script
    }
}
