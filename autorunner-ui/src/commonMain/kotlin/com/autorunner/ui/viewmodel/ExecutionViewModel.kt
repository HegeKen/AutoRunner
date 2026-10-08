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
 * 从界面和悬浮面板驱动 [ExecutionController] 的状态持有者（§6.3、§6.4.3）。
 *
 * 悬浮服务会用同一个 [AppContainer] 创建自己的实例，
 * 因此应用内页面与悬浮面板展示的状态始终一致。
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

    /** 把用户偏好的默认值复制到面板控件中。 */
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
     * 把脚本内部存储的 `execution` 块复制到面板控件（§6.2）。
     *
     * 每当*当前脚本变化*时都会调用，尤其是来自 [start]：脚本库的 ▶ 按钮不经过
     * 面板就直接启动脚本，若没有这一步，保存为「重复 20 次」的脚本会悄无声息地
     * 按全局默认值只跑一次。
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

    /** 根据面板控件构建生效的执行配置。 */
    fun buildConfig(): ExecutionConfig = ExecutionConfig(
        mode = _mode.value,
        repeatCount = _repeatCount.value,
        intervalMs = _intervalMs.value,
        failureStrategy = container.settingsRepository.current.failureStrategy,
    ).sanitized()

    /** 启动选中的脚本。 */
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
            // 切换到另一个脚本时采用*它自己*存储的 execution 块；已选中的脚本
            // 则保留面板上当前显示的值，避免用户手动调过的设置被悄悄覆盖。
            if (_selectedScriptId.value != id) {
                _selectedScriptId.value = id
                adoptExecution(record)
            }
            val config = buildConfig()
            val started = controller.start(record.script, config, scriptId = id)
            // 成功时特意保持安静：已经启动的运行不该用对话框打断用户。
            // 悬浮横幅和进度行会显示正在跑什么；下面的失败仍会弹出消息。
            if (!started) {
                _message.value = "启动失败：已有任务正在执行"
            }
        }
    }

    /** 悬浮球使用的快捷操作：运行第一个可用脚本。 */
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

    /** 面板应提供「启动」入口时为 `true`。 */
    fun canStart(): Boolean = !isRunning && scripts.value.isNotEmpty()

    /** 为分享 / 导出操作序列化选中的脚本。 */
    fun selectedScript(): ScriptModel? = _selectedScriptId.value?.let { id ->
        scripts.value.firstOrNull { it.id == id }?.script
    }
}
