package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.platform.RecordingStatus
import com.autorunner.core.recording.RecordedScriptBuilder
import com.autorunner.core.script.ScriptRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder of the recording screen (§6.1.1).
 *
 * Recording itself happens inside `AutoRunnerAccessibilityService`; this class
 * only drives it and turns the captured actions into a stored script.
 */
class RecordingViewModel(
    private val container: AppContainer,
) {

    private val controller = container.recordingController
    private val repository = container.scriptRepository
    private val scope = container.scope

    val status: StateFlow<RecordingStatus> = controller.status

    /** Classified actions, updated live while recording. */
    val steps: StateFlow<List<ActionStep>> = controller.steps

    /** Raw touch frames seen so far — shown as the "event feed". */
    val eventCount: StateFlow<Int> = controller.eventCount

    private val _name = MutableStateFlow("")

    val name: StateFlow<String> = _name.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    private val _lastSaved = MutableStateFlow<ScriptRecord?>(null)

    val lastSaved: StateFlow<ScriptRecord?> = _lastSaved.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(container.settingsRepository.current.keepScreenOnWhileRunning)

    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    val screenMetrics: ScreenMetrics get() = controller.screenMetrics

    val accessibilityConnected: Boolean get() = container.accessibilityController.isConnected

    /** `true` when the platform is able to capture touches at all. */
    val canRecord: Boolean get() = controller !is com.autorunner.core.platform.UnavailableRecordingController

    val isRecording: Boolean get() = status.value == RecordingStatus.RECORDING

    /** 「待开始」：悬浮控制已就绪，等用户点悬浮球上的开始。 */
    private val _armed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val armed: kotlinx.coroutines.flow.StateFlow<Boolean> = _armed.asStateFlow()

    init {
        if (_name.value.isBlank()) {
            _name.value = defaultName()
        }
    }

    fun setName(value: String) {
        _name.value = value
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
    }

    /** Suggests a fresh name such as `录制脚本 2026-01-15`. */
    fun defaultName(): String = "录制脚本 ${com.autorunner.core.util.isoDatePart(com.autorunner.core.util.currentIsoTimestamp())}"

    /** Starts a session; returns `false` when the service is not connected. */
    fun start(): Boolean {
        if (!accessibilityConnected) {
            _message.value = "请先开启 AutoRunner 无障碍服务"
            return false
        }
        val started = controller.start()
        if (!started) {
            _message.value = "录制启动失败，请检查无障碍服务与悬浮窗权限"
        } else {
            _message.value = null
        }
        return started
    }

    /**
     * App 内点「开始录制」：显示悬浮控制并回到桌面，等用户在悬浮窗上点开始。
     */
    fun armAndGoHome(): Boolean {
        if (!accessibilityConnected) {
            _message.value = "请先开启 AutoRunner 无障碍服务"
            return false
        }
        if (!controller.arm()) {
            _message.value = "无法显示悬浮控制，请检查悬浮窗权限"
            return false
        }
        _armed.value = true
        _message.value = null
        controller.goHome()
        return true
    }

    /**
     * 悬浮服务侧调用：App 内已点「开始录制」，本实例进入待开始状态，
     * 用户单击悬浮球即进入采集。
     */
    fun markArmed() {
        if (!isRecording) _armed.value = true
    }

    /** 用户在悬浮窗上点「开始」：真正进入采集。 */
    fun beginCapture(): Boolean {
        val started = controller.start()
        _armed.value = false
        if (!started) _message.value = "录制启动失败，请检查无障碍服务"
        return started
    }

    /** Stops the session and remembers the produced script. */
    fun stop() {
        val result = controller.stop()
        _armed.value = false
        if (!result.success) {
            _message.value = result.message ?: "录制结束失败"
        } else {
            // 录制结束：把应用带回前台，提示用户及时保存
            controller.bringToFront()
            _message.value = "录制已结束，请及时保存脚本"
        }
    }

    fun cancel() {
        controller.cancel()
        _message.value = "已放弃本次录制"
    }

    fun clear() {
        controller.clear()
    }

    /** Builds (but does not store) the script for the actions captured so far. */
    fun buildScript(): ScriptModel {
        val settings = container.settingsRepository.current
        return RecordedScriptBuilder.build(
            name = _name.value.ifBlank { defaultName() },
            steps = steps.value,
            metrics = screenMetrics,
            execution = settings.defaultExecution,
            recordingConfig = settings.recording,
        )
    }

    /** Persists the recorded actions as a new `.arscript` file. */
    fun saveRecorded() {
        val script = buildScript()
        if (script.flow.isEmpty()) {
            _message.value = "还没有录制到任何动作"
            return
        }
        scope.launch {
            val record = repository.save(null, script)
            _lastSaved.value = record
            _message.value = "已保存「${record.name}」，共 ${record.stepCount} 个动作"
        }
    }

    /** Loads the last recorded script into the editor. */
    fun lastSavedId(): String? = _lastSaved.value?.id

    fun dismissMessage() {
        _message.value = null
    }
}
