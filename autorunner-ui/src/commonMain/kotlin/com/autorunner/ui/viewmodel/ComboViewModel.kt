package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.execution.ComboProgress
import com.autorunner.core.model.ComboBundle
import com.autorunner.core.model.ComboBundleScript
import com.autorunner.core.model.ComboConfig
import com.autorunner.core.model.ComboSlotConfig
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.InputMode
import com.autorunner.core.script.ScriptRecord
import com.autorunner.core.serialization.ComboBundleCodec
import com.autorunner.core.util.currentIsoTimestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 组合页中单个脚本槽位的界面状态。 */
data class ComboSlotState(
    val role: ComboSlotRole,
    val scriptId: String? = null,
    val scriptName: String = "",
    val execution: ExecutionConfig = ComboSlotConfig.defaultExecution(),
) {
    val isSelected: Boolean get() = !scriptId.isNullOrBlank()
}

/**
 * 「组合运行」页面的状态持有者。
 *
 * 负责两件事：
 *
 * 1. 维护两个槽位的脚本选择与执行参数（循环次数 / 间隔 / B 的首轮延迟），
 *    并把它们持久化到 [com.autorunner.core.settings.ComboStore]；
 * 2. 把「开始组合」翻译成同一个「武装到悬浮球 → 回到桌面 → 用户打开目标应用后
 *    单击悬浮球」的启动流程，与单脚本运行的交互保持一致。
 *
 * 真正的并行执行与状态聚合在 [com.autorunner.core.execution.ExecutionController]
 * 中完成；本类只读取 `state` / `comboProgress` / `lastReport`。
 */
class ComboViewModel(
    private val container: AppContainer,
) {

    private val controller = container.executionController
    private val repository = container.scriptRepository
    private val store = container.comboStore
    private val scope = container.scope

    val scripts: StateFlow<List<ScriptRecord>> = repository.scripts

    val state: StateFlow<ExecutionState> = controller.state

    val comboProgress: StateFlow<ComboProgress?> = controller.comboProgress

    /** 已武装到悬浮球、等待单击开始的组合配置。 */
    val armedCombo: StateFlow<ComboConfig?> = controller.armedCombo

    private val _slotA = MutableStateFlow(ComboSlotState(ComboSlotRole.A))

    val slotA: StateFlow<ComboSlotState> = _slotA.asStateFlow()

    private val _slotB = MutableStateFlow(ComboSlotState(ComboSlotRole.B))

    val slotB: StateFlow<ComboSlotState> = _slotB.asStateFlow()

    /** 最近一次组合运行的汇总报告；运行结束后回到页面仍可读取。 */
    val lastReport: StateFlow<ExecutionReport?> = controller.lastComboReport

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    val isRunning: Boolean get() = state.value.isActive

    val accessibilityConnected: Boolean get() = container.accessibilityController.isConnected

    val rootInputAvailable: Boolean get() = container.rootInputBackend?.isAvailable == true

    init {
        refreshScripts()
        restore()
    }

    fun refreshScripts() {
        scope.launch { repository.refresh() }
    }

    fun selectScript(role: ComboSlotRole, scriptId: String) {
        scope.launch {
            val record = repository.load(scriptId)
            if (record == null) {
                _message.value = "脚本不存在，可能已被删除"
                return@launch
            }
            val current = slot(role).value
            // 组合天生是循环语义：脚本本身是「单次执行」时，默认切换成无限循环，
            // 用户仍可在下方改回单次或指定次数。
            val base = record.script.execution
            val combined = if (base.mode == ExecutionMode.ONCE) {
                base.copy(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = ExecutionConfig.INFINITE_REPEAT,
                )
            } else {
                base
            }
            val execution = combined.copy(
                // 首轮延迟是组合级参数：切换脚本时保留用户已经设好的 B 延迟。
                startDelayMs = if (role == ComboSlotRole.B) current.execution.startDelayMs else 0L,
                failureStrategy = container.settingsRepository.current.failureStrategy,
            ).sanitized()
            setSlot(
                role,
                ComboSlotState(
                    role = role,
                    scriptId = record.id,
                    scriptName = record.name,
                    execution = execution,
                ),
            )
            persist()
        }
    }

    fun setMode(role: ComboSlotRole, mode: ExecutionMode) {
        updateExecution(role) { it.copy(mode = mode) }
    }

    fun setRepeatCount(role: ComboSlotRole, count: Int) {
        updateExecution(role) { current ->
            current.copy(
                repeatCount = count.coerceAtLeast(0),
                mode = if (count != 1 && current.mode == ExecutionMode.ONCE) {
                    ExecutionMode.REPEAT
                } else {
                    current.mode
                },
            )
        }
    }

    fun setIntervalMs(role: ComboSlotRole, intervalMs: Long) {
        updateExecution(role) { it.copy(intervalMs = intervalMs.coerceAtLeast(0L)) }
    }

    /** 设置 B 的第一轮相对组合启动的延迟；A 的延迟始终为 0。 */
    fun setStartDelayMs(startDelayMs: Long) {
        updateExecution(ComboSlotRole.B) { it.copy(startDelayMs = startDelayMs.coerceAtLeast(0L)) }
    }

    /**
     * 开始组合：先保存配置，再把两个槽位武装到悬浮球并回到桌面。
     *
     * 与单脚本运行一致，用户打开目标应用后单击悬浮球才会真正派发动作，
     * 首轮延迟从单击悬浮球的那一刻开始计时。
     */
    fun start() {
        if (controller.isActive) {
            _message.value = "已有任务正在执行"
            return
        }
        val a = _slotA.value
        val b = _slotB.value
        if (!a.isSelected || !b.isSelected) {
            _message.value = "请先为脚本 A 和脚本 B 各选择一个脚本"
            return
        }
        if (a.scriptId == b.scriptId) {
            _message.value = "请选择两个不同的脚本"
            return
        }
        if (!accessibilityConnected && !rootInputAvailable) {
            _message.value = if (inputMode == InputMode.ROOT) {
                "Root 注入未就绪：请在 root 管理器中允许本应用，或刷入 AutoRunner Root Bridge 模块"
            } else {
                "无障碍服务未连接，无法执行"
            }
            return
        }
        val overlay = container.overlayManager
        if (!overlay.isPermissionGranted()) {
            _message.value = "请先授予悬浮窗权限，运行时会用它显示开始按钮"
            return
        }

        val config = buildConfig()
        scope.launch {
            val recordA = repository.load(a.scriptId!!)
            val recordB = repository.load(b.scriptId!!)
            if (recordA == null || recordB == null) {
                _message.value = "脚本不存在，可能已被删除"
                return@launch
            }
            if (recordA.script.flow.isEmpty() || recordB.script.flow.isEmpty()) {
                _message.value = "组合中的脚本不包含任何动作"
                return@launch
            }
            store.save(config)
            if (!overlay.armCombo(config)) {
                _message.value = "无法显示悬浮控制，请检查悬浮窗权限"
                return@launch
            }
            overlay.goHome()
        }
    }

    /** 取消已武装、但还没开始的组合（回到应用后可用）。 */
    fun disarm() {
        controller.disarm()
    }

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun togglePause() = controller.togglePause()

    fun stop() = controller.stop()

    fun reset() {
        controller.reset()
        _message.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** 供运行控制等场景直接填入一条提示。 */
    fun reportMessage(message: String) {
        _message.value = message
    }

    /**
     * 生成自包含的组合任务文件内容。
     *
     * 导出把两段脚本原文一起嵌入；槽位里保存的循环参数与 B 的首轮延迟也
     * 一并保留，导入端无需依赖原有脚本 id。
     */
    suspend fun buildExportFile(): Pair<String, String>? {
        val a = _slotA.value
        val b = _slotB.value
        if (!a.isSelected || !b.isSelected) {
            _message.value = "请先为脚本 A 和脚本 B 各选择一个脚本"
            return null
        }
        if (a.scriptId == b.scriptId) {
            _message.value = "A / B 选择了同一个脚本，无法导出组合"
            return null
        }
        val recordA = repository.load(a.scriptId!!) ?: run {
            _message.value = "脚本 A 已被删除，无法导出"
            return null
        }
        val recordB = repository.load(b.scriptId!!) ?: run {
            _message.value = "脚本 B 已被删除，无法导出"
            return null
        }
        val embedded = buildList {
            add(ComboBundleScript(recordA.id, recordA.script))
            if (recordB.id != recordA.id) add(ComboBundleScript(recordB.id, recordB.script))
        }
        val bundle = ComboBundle(
            exportedAt = currentIsoTimestamp(),
            combo = buildConfig(),
            scripts = embedded,
        )
        return ComboBundleCodec.fileNameFor(bundle) to ComboBundleCodec.encode(bundle)
    }

    /**
     * 导入 `.arcombo` 组合任务：先落盘嵌入的脚本（或复用内容相同的已有脚本），
     * 再把两个槽位重映射到新脚本 id，最后保存组合配置。
     */
    fun importCombo(text: String) {
        if (controller.isActive) {
            _message.value = "运行中不能导入组合，请先停止当前任务"
            return
        }
        scope.launch {
            val bundle = ComboBundleCodec.decode(text)
            if (bundle == null) {
                _message.value = "不是有效的组合任务文件（.arcombo）"
                return@launch
            }
            repository.refresh()
            val scriptCache = mutableMapOf<String, ScriptRecord>()

            suspend fun recordFor(originalId: String, fallbackIndex: Int): ScriptRecord? {
                scriptCache[originalId]?.let { return it }
                val embedded = bundle.scripts.firstOrNull { it.originalId == originalId }
                    ?: bundle.scripts.getOrNull(fallbackIndex)
                if (embedded != null) {
                    val saved = saveBundleScript(embedded)
                    if (saved != null) {
                        if (embedded.originalId.isNotBlank()) {
                            scriptCache[embedded.originalId] = saved
                        }
                        if (originalId.isNotBlank()) scriptCache[originalId] = saved
                        return saved
                    }
                }
                if (originalId.isBlank()) return null
                return repository.load(originalId)?.also { scriptCache[originalId] = it }
            }

            val config = bundle.combo.sanitized()
            val recordA = recordFor(config.slotA.scriptId, 0)
            val recordB = recordFor(config.slotB.scriptId, 1)
            if (recordA == null || recordB == null) {
                _message.value = "组合文件中的脚本无法导入，请检查文件是否完整"
                return@launch
            }
            if (recordA.id == recordB.id) {
                _message.value = "组合文件中的 A / B 是同一个脚本，无法运行"
                return@launch
            }
            val slotA = ComboSlotState(
                role = ComboSlotRole.A,
                scriptId = recordA.id,
                scriptName = recordA.name,
                execution = config.slotA.execution.sanitized().copy(startDelayMs = 0L),
            )
            val slotB = ComboSlotState(
                role = ComboSlotRole.B,
                scriptId = recordB.id,
                scriptName = recordB.name,
                execution = config.slotB.execution.sanitized(),
            )
            setSlot(ComboSlotRole.A, slotA)
            setSlot(ComboSlotRole.B, slotB)
            // 旧配置可能还武装在悬浮球上；导入后取消，避免下次单击启动旧组合。
            controller.disarm()
            persist()
            _message.value = "已导入组合：${recordA.name} + ${recordB.name}"
        }
    }

    private suspend fun saveBundleScript(embedded: ComboBundleScript): ScriptRecord? {
        val payload = embedded.script
        if (embedded.originalId.isNotBlank()) {
            repository.load(embedded.originalId)?.let { existing ->
                if (existing.script == payload) return existing
            }
        }
        scripts.value.firstOrNull { it.script == payload }?.let { return it }
        return runCatching { repository.save(null, payload) }.getOrNull()
    }

    /** 组合运行所需的完整配置；启动前会写回 [ComboStore]。 */
    fun buildConfig(): ComboConfig = ComboConfig(
        slotA = ComboSlotConfig(
            scriptId = _slotA.value.scriptId.orEmpty(),
            execution = _slotA.value.execution.copy(startDelayMs = 0L),
        ),
        slotB = ComboSlotConfig(
            scriptId = _slotB.value.scriptId.orEmpty(),
            execution = _slotB.value.execution,
        ),
    ).sanitized()

    private val inputMode: InputMode get() = container.settingsRepository.current.inputMode

    private fun restore() {
        val saved = store.load()
        _slotA.value = saved.slotA.toUiState(ComboSlotRole.A)
        _slotB.value = saved.slotB.toUiState(ComboSlotRole.B)
        scope.launch {
            val missing = mutableListOf<String>()
            saved.slotA.scriptId.takeIf { it.isNotBlank() }?.let { id ->
                val record = repository.load(id)
                if (record == null) {
                    missing += "A"
                    _slotA.value = _slotA.value.copy(scriptId = null, scriptName = "")
                } else {
                    _slotA.value = _slotA.value.copy(scriptName = record.name)
                }
            }
            saved.slotB.scriptId.takeIf { it.isNotBlank() }?.let { id ->
                val record = repository.load(id)
                if (record == null) {
                    missing += "B"
                    _slotB.value = _slotB.value.copy(scriptId = null, scriptName = "")
                } else {
                    _slotB.value = _slotB.value.copy(scriptName = record.name)
                }
            }
            if (missing.isNotEmpty()) {
                _message.value = "上次保存的脚本 ${missing.joinToString(" / ")} 已不存在，请重新选择"
                persist()
            }
        }
    }

    private fun slot(role: ComboSlotRole): StateFlow<ComboSlotState> = when (role) {
        ComboSlotRole.A -> _slotA
        ComboSlotRole.B -> _slotB
    }

    private fun setSlot(role: ComboSlotRole, state: ComboSlotState) {
        when (role) {
            ComboSlotRole.A -> _slotA.value = state
            ComboSlotRole.B -> _slotB.value = state
        }
    }

    private fun updateExecution(role: ComboSlotRole, transform: (ExecutionConfig) -> ExecutionConfig) {
        val current = slot(role).value
        val next = transform(current.execution).sanitized().let {
            if (role == ComboSlotRole.A) it.copy(startDelayMs = 0L) else it
        }
        setSlot(role, current.copy(execution = next))
        persist()
    }

    private fun persist() {
        store.save(buildConfig())
    }

    private fun ComboSlotConfig.toUiState(role: ComboSlotRole): ComboSlotState = ComboSlotState(
        role = role,
        scriptId = scriptId.takeIf { it.isNotBlank() },
        scriptName = "",
        execution = execution.sanitized().let {
            if (role == ComboSlotRole.A) it.copy(startDelayMs = 0L) else it
        },
    )
}
