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
 * 可视化脚本编辑器的状态持有者（§6.5）。
 *
 * 编辑中的动作流保存在普通 `List<ActionStep>` 里；所有变更都经由本类，
 * 校验、脏标记跟踪与预览因此始终保持同步。
 */
class ScriptEditorViewModel(
    private val container: AppContainer,
) {

    private val repository = container.scriptRepository
    private val scope = container.scope

    private val _scriptId = MutableStateFlow<String?>(null)

    /** 编辑全新脚本时为 `null`。 */
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

    /** 本脚本 `gamepad` 动作所依据的手柄预设。 */
    val gamepadMode: StateFlow<GamepadMode> = _gamepadMode.asStateFlow()

    private val _selectedIndex = MutableStateFlow<Int?>(null)

    val selectedIndex: StateFlow<Int?> = _selectedIndex.asStateFlow()

    private val _dirty = MutableStateFlow(false)

    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    private val _savedAt = MutableStateFlow<String?>(null)

    val savedAt: StateFlow<String?> = _savedAt.asStateFlow()

    /** 用于预览绝对坐标 / 归一化坐标换算的分辨率。 */
    val screenMetrics: ScreenMetrics
        get() = container.accessibilityController.screenMetrics.takeIf { it.isValid }
            ?: ScreenMetrics.Unknown

    private val _validation = MutableStateFlow(ValidationResult.Valid)

    /**
     * 当前草稿的校验结果。每次变更都同步重算，
     * 编辑器的内联提示因此永远不会落后于 UI。
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

    /** 加载 [id]；传 `null` 表示新建空白草稿。 */
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
     * 删除一个步骤。
     *
     * [followStep] 为 `true` 时把选中项移到相邻步骤上，让正在编辑参数的调用方
     * （详情窗格）停留在动作列表；时间线列表传 `false`，这样删除一行不会把
     * 手机端编辑器导航进详情页。
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

    /** 复制一个步骤；[followStep] 的含义见 [removeStep]。 */
    fun duplicateStep(index: Int, followStep: Boolean = true) {
        val current = _flow.value.toMutableList()
        if (index !in current.indices) return
        current.add(index + 1, current[index])
        _flow.value = current
        if (followStep) _selectedIndex.value = index + 1
        _dirty.value = true
        revalidate()
    }

    /** 移动一个步骤；时间线列表与详情窗格都会用到。 */
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

    /** 选择本脚本 `gamepad` 动作所针对的手柄预设。 */
    fun setGamepadMode(mode: GamepadMode) {
        _gamepadMode.value = mode
        _dirty.value = true
        revalidate()
    }

    /**
     * 在绝对像素与 `0.0~1.0` 百分比（§6.2）之间切换，并重写每个动作，
     * 使动作流始终保持在 `info.coordinateSpace` 字段声明的坐标空间里。
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

    /** 保存草稿；通过 [onSaved] 把存储后的记录交还调用方。 */
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

    /** 把草稿序列化为格式化 JSON，交给导出流程。 */
    fun exportText(): String = container.codec.encode(currentScript, formatted = true)

    fun suggestedFileName(): String = com.autorunner.core.model.ArScriptConventions.fileNameFor(
        _name.value.ifBlank { "autorunner_script" },
    )

    fun dismissMessage() {
        _message.value = null
    }

    /** `flow` 条目解析为绝对像素后的结果（预览面板使用）。 */
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

    /** 编辑器用它判断某一步能否上移 / 下移。 */
    fun canMoveUp(index: Int): Boolean = index > 0

    fun canMoveDown(index: Int): Boolean = index < _flow.value.lastIndex
}
