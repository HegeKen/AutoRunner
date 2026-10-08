package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.CoordinateResolver
import com.autorunner.core.model.CoordinateSpace
import com.autorunner.core.model.DeviceInfo
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.FailureStrategy
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptInfo
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.script.ScriptRecord
import com.autorunner.core.serialization.ScriptValidator
import com.autorunner.core.serialization.ValidationResult
import com.autorunner.core.util.currentIsoTimestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder of the visual script editor (§6.5).
 *
 * The edited flow lives in a plain `List<ActionStep>`; every mutation goes
 * through this class so that validation, dirty tracking and the preview all
 * stay in sync.
 */
class ScriptEditorViewModel(
    private val container: AppContainer,
) {

    private val repository = container.scriptRepository
    private val scope = container.scope

    private val _scriptId = MutableStateFlow<String?>(null)

    /** `null` while editing a brand new script. */
    val scriptId: StateFlow<String?> = _scriptId.asStateFlow()

    private val _name = MutableStateFlow("")

    val name: StateFlow<String> = _name.asStateFlow()

    private val _description = MutableStateFlow("")

    val description: StateFlow<String> = _description.asStateFlow()

    private val _flow = MutableStateFlow<List<ActionStep>>(emptyList())

    val flow: StateFlow<List<ActionStep>> = _flow.asStateFlow()

    private val _execution = MutableStateFlow(ExecutionConfig())

    val execution: StateFlow<ExecutionConfig> = _execution.asStateFlow()

    private val _coordinateSpace = MutableStateFlow(CoordinateSpace.ABSOLUTE)

    val coordinateSpace: StateFlow<CoordinateSpace> = _coordinateSpace.asStateFlow()

    private val _gamepadMode = MutableStateFlow(GamepadMode.Default)

    /** Gamepad preset this script's `gamepad` actions are authored against. */
    val gamepadMode: StateFlow<GamepadMode> = _gamepadMode.asStateFlow()

    private val _selectedIndex = MutableStateFlow<Int?>(null)

    val selectedIndex: StateFlow<Int?> = _selectedIndex.asStateFlow()

    private val _dirty = MutableStateFlow(false)

    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    private val _savedAt = MutableStateFlow<String?>(null)

    val savedAt: StateFlow<String?> = _savedAt.asStateFlow()

    /** Resolution used to preview absolute / normalised conversion. */
    val screenMetrics: ScreenMetrics
        get() = container.accessibilityController.screenMetrics.takeIf { it.isValid }
            ?: ScreenMetrics.Unknown

    private val _validation = MutableStateFlow(ValidationResult.Valid)

    /**
     * Validation of the current draft. Recomputed synchronously on every
     * mutation so that the editor's inline hints can never lag behind the UI.
     */
    val validation: StateFlow<ValidationResult> = _validation.asStateFlow()

    private fun revalidate() {
        _validation.value = ScriptValidator.validate(
            currentScript,
            screenMetrics.takeIf { it.isValid },
        )
    }

    val currentScript: ScriptModel
        get() = buildScript(
            _flow.value,
            _execution.value,
            _coordinateSpace.value,
            _name.value,
            _description.value,
            _gamepadMode.value,
        )

    val selectedStep: ActionStep?
        get() = _selectedIndex.value?.let { index -> _flow.value.getOrNull(index) }

    /** Loads [id]; pass `null` to start an empty draft. */
    fun load(id: String?) {
        scope.launch {
            if (id == null) {
                val settings = container.settingsRepository.current
                _scriptId.value = null
                _name.value = ""
                _description.value = ""
                _flow.value = emptyList()
                _execution.value = settings.defaultExecution
                _coordinateSpace.value = CoordinateSpace.ABSOLUTE
                _gamepadMode.value = settings.gamepadMode
                _selectedIndex.value = null
                _dirty.value = false
                _savedAt.value = null
                revalidate()
                return@launch
            }
            val record = repository.load(id) ?: run {
                _message.value = "脚本不存在或已损坏"
                return@launch
            }
            _scriptId.value = record.id
            _name.value = record.script.info.name
            _description.value = record.script.info.description
            _flow.value = record.script.flow
            _execution.value = record.script.execution
            _coordinateSpace.value = record.script.info.coordinateSpace
            _gamepadMode.value = record.script.info.gamepadMode
            // 先展示动作/步骤列表，由用户点选某个动作后再进入参数详情；
            // 之前自动选中第一个动作，手机上会直接落到详情页。
            _selectedIndex.value = null
            _dirty.value = false
            _savedAt.value = record.script.info.createdAt
            revalidate()
        }
    }

    fun setName(value: String) {
        _name.value = value
        _dirty.value = true
        revalidate()
    }

    fun setDescription(value: String) {
        _description.value = value
        _dirty.value = true
        revalidate()
    }

    fun select(index: Int?) {
        _selectedIndex.value = index
    }

    fun addStep(step: ActionStep) {
        _flow.value = _flow.value + step
        _selectedIndex.value = _flow.value.lastIndex
        _dirty.value = true
        revalidate()
    }

    fun updateStep(index: Int, step: ActionStep) {
        val current = _flow.value.toMutableList()
        if (index !in current.indices) return
        current[index] = step
        _flow.value = current
        _dirty.value = true
        revalidate()
    }

    /**
     * Removes a step.
     *
     * [followStep] keeps the selection on an existing neighbour so a caller that
     * is editing parameters (the detail pane) stays on the list of actions. The
     * timeline list passes `false`, so deleting a row never navigates the phone
     * editor into the detail page.
     */
    fun removeStep(index: Int, followStep: Boolean = true) {
        val current = _flow.value.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        _flow.value = current
        val selected = _selectedIndex.value
        _selectedIndex.value = when {
            followStep -> when {
                current.isEmpty() -> null
                index >= current.size -> current.lastIndex
                else -> index
            }
            selected == null || current.isEmpty() -> null
            else -> selected.coerceAtMost(current.lastIndex)
        }
        _dirty.value = true
        revalidate()
    }

    /** Duplicates a step; see [removeStep] for [followStep]. */
    fun duplicateStep(index: Int, followStep: Boolean = true) {
        val current = _flow.value.toMutableList()
        if (index !in current.indices) return
        current.add(index + 1, current[index])
        _flow.value = current
        if (followStep) _selectedIndex.value = index + 1
        _dirty.value = true
        revalidate()
    }

    /** Moves a step; used by both the timeline list and the detail pane. */
    fun moveStep(from: Int, to: Int, followStep: Boolean = true) {
        val current = _flow.value.toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        val step = current.removeAt(from)
        current.add(to, step)
        _flow.value = current
        if (followStep) _selectedIndex.value = to
        _dirty.value = true
    }

    fun clearFlow() {
        _flow.value = emptyList()
        _selectedIndex.value = null
        _dirty.value = true
        revalidate()
    }

    fun setMode(mode: ExecutionMode) {
        _execution.value = _execution.value.copy(mode = mode).sanitized()
        _dirty.value = true
        revalidate()
    }

    fun setRepeatCount(count: Int) {
        _execution.value = _execution.value.copy(
            mode = if (count == 1) ExecutionMode.ONCE else ExecutionMode.REPEAT,
            repeatCount = count.coerceAtLeast(0),
        ).sanitized()
        _dirty.value = true
        revalidate()
    }

    fun setIntervalMs(intervalMs: Long) {
        _execution.value = _execution.value.copy(intervalMs = intervalMs.coerceAtLeast(0)).sanitized()
        _dirty.value = true
        revalidate()
    }

    fun setFailureStrategy(strategy: FailureStrategy) {
        _execution.value = _execution.value.copy(failureStrategy = strategy)
        _dirty.value = true
        revalidate()
    }

    /** Selects the gamepad preset this script's `gamepad` actions target. */
    fun setGamepadMode(mode: GamepadMode) {
        _gamepadMode.value = mode
        _dirty.value = true
        revalidate()
    }

    /**
     * Switches between absolute pixels and `0.0~1.0` fractions (§6.2) and
     * rewrites every action so the flow stays in the coordinate space the
     * `info.coordinateSpace` field advertises.
     */
    fun toggleCoordinateSpace() {
        val metrics = screenMetrics
        if (!metrics.isValid) {
            _message.value = "尚未连接无障碍服务，无法换算坐标"
            return
        }
        val from = _coordinateSpace.value
        val to = if (from == CoordinateSpace.ABSOLUTE) CoordinateSpace.NORMALIZED else CoordinateSpace.ABSOLUTE
        _flow.value = _flow.value.map { step ->
            if (to == CoordinateSpace.NORMALIZED) {
                CoordinateResolver.normalise(step, metrics)
            } else {
                CoordinateResolver.resolve(step, from, metrics)
            }
        }
        _coordinateSpace.value = to
        _message.value = if (to == CoordinateSpace.NORMALIZED) {
            "已切换为百分比坐标，可在不同分辨率设备上复用"
        } else {
            "已切换为绝对坐标（${metrics.widthPx}×${metrics.heightPx}）"
        }
        _dirty.value = true
        revalidate()
    }

    /** Saves the draft; returns the stored record through [onSaved]. */
    fun save(onSaved: (ScriptRecord) -> Unit = {}) {
        val script = currentScript
        scope.launch {
            val record = repository.save(_scriptId.value, script)
            _scriptId.value = record.id
            _savedAt.value = record.script.info.createdAt
            _dirty.value = false
            _message.value = "已保存「${record.name}」"
            revalidate()
            onSaved(record)
        }
    }

    /** Serialises the draft as pretty JSON and hands it to the export flow. */
    fun exportText(): String = container.codec.encode(currentScript, formatted = true)

    fun suggestedFileName(): String = com.autorunner.core.model.ArScriptConventions.fileNameFor(
        _name.value.ifBlank { "autorunner_script" },
    )

    fun dismissMessage() {
        _message.value = null
    }

    /** `flow` entries resolved to absolute pixels (used by the preview pane). */
    fun previewFlow(): List<ActionStep> {
        val metrics = screenMetrics
        if (!metrics.isValid) return _flow.value
        return _flow.value.map { CoordinateResolver.resolve(it, _coordinateSpace.value, metrics) }
    }

    private fun buildScript(
        steps: List<ActionStep>,
        config: ExecutionConfig,
        space: CoordinateSpace,
        name: String,
        description: String,
        gamepadMode: GamepadMode,
    ): ScriptModel {
        val metrics = screenMetrics
        return ScriptModel(
            info = ScriptInfo(
                name = name,
                description = description,
                device = if (metrics.isValid) DeviceInfo.of(metrics) else DeviceInfo(),
                createdAt = _savedAt.value ?: currentIsoTimestamp(),
                coordinateSpace = space,
                gamepadMode = gamepadMode,
            ),
            execution = config.sanitized(),
            flow = steps,
        )
    }

    /** Convenience used by the editor to know whether a step can move up/down. */
    fun canMoveUp(index: Int): Boolean = index > 0

    fun canMoveDown(index: Int): Boolean = index < _flow.value.lastIndex
}
