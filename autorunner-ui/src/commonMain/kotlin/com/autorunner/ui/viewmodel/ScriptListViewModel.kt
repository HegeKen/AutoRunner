package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.script.ImportResult
import com.autorunner.core.script.ScriptRecord
import com.autorunner.ui.platform.ScriptTransferController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the script library screen.
 *
 * Owns loading, searching, rename/duplicate/delete and the import/export
 * round trip through the platform [ScriptTransferController].
 */
class ScriptListViewModel(
    private val container: AppContainer,
    private val transfer: ScriptTransferController? = null,
) {

    private val repository = container.scriptRepository
    private val scope = container.scope

    /** All stored scripts, newest first. */
    val scripts: StateFlow<List<ScriptRecord>> = repository.scripts

    private val _query = MutableStateFlow("")

    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)

    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** One shot user feedback rendered in a MIUIX dialog. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)

    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _visibleScripts = MutableStateFlow<List<ScriptRecord>>(emptyList())

    /**
     * Scripts matching [query] (case insensitive, name / description / tags).
     *
     * Recomputed whenever the query changes or the repository emits, instead of
     * being derived through `stateIn`, so the list is never one frame behind.
     */
    val visibleScripts: StateFlow<List<ScriptRecord>> = _visibleScripts.asStateFlow()

    init {
        scope.launch { repository.scripts.collect { applyFilter() } }
        refresh()
        transfer?.let { controller ->
            controller.onPicked = { fileName, content -> importText(content, fileName) }
        }
    }

    fun refresh() {
        scope.launch { repository.refresh() }
    }

    fun setQuery(value: String) {
        _query.value = value
        applyFilter()
    }

    private fun applyFilter() {
        val list = scripts.value
        val needle = _query.value.trim().lowercase()
        _visibleScripts.value = if (needle.isEmpty()) {
            list
        } else {
            list.filter { record ->
                record.name.lowercase().contains(needle) ||
                    record.description.lowercase().contains(needle) ||
                    record.script.info.tags.any { tag -> tag.lowercase().contains(needle) }
            }
        }
    }

    fun select(id: String?) {
        _selectedId.value = id
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun delete(id: String) {
        scope.launch {
            val record = repository.load(id)
            if (repository.delete(id)) {
                if (_selectedId.value == id) _selectedId.value = null
                _message.value = "已删除「${record?.name ?: id}」"
            } else {
                _message.value = "删除失败"
            }
        }
    }

    fun duplicate(id: String) {
        scope.launch {
            val copy = repository.duplicate(id)
            _message.value = if (copy != null) "已创建副本「${copy.name}」" else "复制失败"
        }
    }

    fun rename(id: String, newName: String) {
        scope.launch {
            val renamed = repository.rename(id, newName)
            _message.value = if (renamed != null) "已重命名为「${renamed.name}」" else "重命名失败"
        }
    }

    fun deleteAll() {
        scope.launch {
            val count = repository.deleteAll()
            _selectedId.value = null
            _message.value = "已清空 $count 个脚本"
        }
    }

    /** Imports a `.arscript` payload (from SAF, clipboard or a share intent). */
    fun importText(content: String, fileName: String? = null) {
        scope.launch {
            // 兼容浏览器/系统重复下载时附加的「 (1)」，如「测试.arscript (1)」。
            val normalizedName = fileName?.trim()?.replace(DUPLICATE_SUFFIX, "")
            if (fileName != null &&
                normalizedName?.endsWith(SCRIPT_SUFFIX, ignoreCase = true) != true &&
                normalizedName?.endsWith(".json", ignoreCase = true) != true
            ) {
                _message.value = "请选择 .arscript 文件（当前选择的是「$fileName」）"
                return@launch
            }
            _busy.value = true
            when (val result = repository.importFromText(content, fileName)) {
                is ImportResult.Success -> {
                    _selectedId.value = result.record.id
                    _message.value = buildString {
                        append("已导入「${result.record.name}」")
                        if (result.warnings.isNotEmpty()) {
                            append("（")
                            append(result.warnings.first())
                            append("）")
                        }
                    }
                }

                is ImportResult.Failure -> _message.value = "导入失败：${result.message}"
            }
            _busy.value = false
        }
    }

    fun importFromClipboard() {
        _message.value = "请使用「导入」选择 .arscript 文件，或粘贴脚本文本"
    }

    /** Serialises [id] and hands it to the platform export flow. */
    fun export(id: String) {
        scope.launch {
            val record = repository.load(id) ?: run {
                _message.value = "脚本不存在"
                return@launch
            }
            if (record.stepCount == 0) {
                _message.value = "「${record.name}」不包含任何动作"
                return@launch
            }
            val content = repository.exportToText(id) ?: run {
                _message.value = "导出失败"
                return@launch
            }
            val fileName = repository.suggestedFileName(id)
            val controller = transfer
            if (controller == null) {
                _message.value = "当前平台不支持文件导出"
            } else {
                controller.exportScript(fileName, content)
                _message.value = "已生成 ${fileName}，请在系统文件选择器中选择保存位置"
            }
        }
    }

    /** Shares the serialised script (system share sheet), with clipboard fallback. */
    fun share(id: String) {
        scope.launch {
            val content = repository.exportToText(id) ?: run {
                _message.value = "导出失败"
                return@launch
            }
            val fileName = repository.suggestedFileName(id)
            val controller = transfer
            if (controller == null) {
                _message.value = "当前平台不支持分享"
                return@launch
            }
            controller.shareScript(fileName, content)
        }
    }

    fun copyToClipboard(id: String) {
        scope.launch {
            val content = repository.exportToText(id) ?: return@launch
            transfer?.copyToClipboard(repository.suggestedFileName(id), content)
            _message.value = "脚本 JSON 已复制到剪贴板"
        }
    }

    fun launchImportPicker() {
        transfer?.pickScriptFile() ?: run { _message.value = "当前平台不支持文件导入" }
    }

    private companion object {
        const val SCRIPT_SUFFIX = ".arscript"

        /** 重复下载时系统附加的「 (1)」「(2)」等尾部编号。 */
        val DUPLICATE_SUFFIX = Regex("""\s*\(\d+\)\s*$""")
    }

    /** Saves a script that was produced by the recording screen. */
    fun save(script: ScriptModel, onSaved: ((ScriptRecord) -> Unit)? = null) {
        scope.launch {
            val record = repository.save(null, script)
            _message.value = "已保存「${record.name}」"
            onSaved?.invoke(record)
        }
    }
}
