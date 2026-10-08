package com.autorunner.core.script

import com.autorunner.core.model.ArScriptConventions
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ScriptModel
import kotlinx.coroutines.flow.StateFlow

/**
 * 已存储的脚本，加上 UI 列表所需的簿记信息。
 */
data class ScriptRecord(
    /** 稳定标识符；同时是磁盘上的文件基名。 */
    val id: String,
    val fileName: String,
    val script: ScriptModel,
    val updatedAtMs: Long,
    val sizeBytes: Int,
) {
    val name: String get() = script.displayName()

    val stepCount: Int get() = script.stepCount

    val execution: ExecutionConfig get() = script.execution

    val description: String get() = script.info.description

    val createdAt: String get() = script.info.createdAt

    /** `12 个动作 · 重复 10 次 · 间隔 2000ms` */
    fun summary(): String = buildString {
        append(stepCount)
        append(" 个动作")
        if (stepCount > 0) {
            append(" · ")
            append(execution.describe())
        }
    }
}

/** 导入 `.arscript` 载荷的结果。 */
sealed interface ImportResult {
    data class Success(val record: ScriptRecord, val warnings: List<String> = emptyList()) : ImportResult

    data class Failure(val message: String) : ImportResult
}

/**
 * `.arscript` 文件的持久化边界。
 *
 * 编辑器、悬浮面板和导入器都经由该接口，把存储后端（Android 上的应用
 * 私有文件、JVM 上的一个目录）挡在 UI 层之外。
 */
interface ScriptRepository {

    /** 所有已存储脚本的最新快照，最新的在前。 */
    val scripts: StateFlow<List<ScriptRecord>>

    /** 重新读取底层存储并更新 [scripts]。 */
    suspend fun refresh(): List<ScriptRecord>

    suspend fun load(id: String): ScriptRecord?

    /** 创建或替换一个脚本。 */
    suspend fun save(id: String?, script: ScriptModel): ScriptRecord

    suspend fun rename(id: String, newName: String): ScriptRecord?

    suspend fun duplicate(id: String): ScriptRecord?

    suspend fun delete(id: String): Boolean

    suspend fun deleteAll(): Int

    /** 把已存储的脚本序列化为带缩进的 `.arscript` JSON。 */
    suspend fun exportToText(id: String): String?

    /** 解析并存储 `.arscript` 载荷（`fileName` 用于生成 id）。 */
    suspend fun importFromText(
        text: String,
        fileName: String? = null,
        overwrite: Boolean = false,
    ): ImportResult

    /** 导出 [id] 时建议的文件名。 */
    suspend fun suggestedFileName(id: String): String
}

/** 便捷函数：所有已存储文件使用的扩展名。 */
internal fun scriptFileName(baseName: String): String = "$baseName${ArScriptConventions.DOT_EXTENSION}"

/** 去掉已存储文件名中的 `.arscript` 后缀。 */
internal fun scriptBaseName(fileName: String): String =
    fileName.removeSuffix(ArScriptConventions.DOT_EXTENSION).ifBlank { fileName }
