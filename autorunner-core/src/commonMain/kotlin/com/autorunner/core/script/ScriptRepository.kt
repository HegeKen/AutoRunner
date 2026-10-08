package com.autorunner.core.script

import com.autorunner.core.model.ArScriptConventions
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ScriptModel
import kotlinx.coroutines.flow.StateFlow

/**
 * A stored script plus the bookkeeping the UI needs for its list.
 */
data class ScriptRecord(
    /** Stable identifier; also the file base name on disk. */
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

/** Outcome of importing a `.arscript` payload. */
sealed interface ImportResult {
    data class Success(val record: ScriptRecord, val warnings: List<String> = emptyList()) : ImportResult

    data class Failure(val message: String) : ImportResult
}

/**
 * Persistence boundary for `.arscript` files.
 *
 * The editor, the floating panel and the importer all go through this
 * interface, which keeps the storage backend (app private files on Android,
 * a directory on the JVM) out of the UI layer.
 */
interface ScriptRepository {

    /** Latest snapshot of all stored scripts, newest first. */
    val scripts: StateFlow<List<ScriptRecord>>

    /** Re-reads the backing storage and updates [scripts]. */
    suspend fun refresh(): List<ScriptRecord>

    suspend fun load(id: String): ScriptRecord?

    /** Creates or replaces a script. */
    suspend fun save(id: String?, script: ScriptModel): ScriptRecord

    suspend fun rename(id: String, newName: String): ScriptRecord?

    suspend fun duplicate(id: String): ScriptRecord?

    suspend fun delete(id: String): Boolean

    suspend fun deleteAll(): Int

    /** Serialises a stored script to pretty `.arscript` JSON. */
    suspend fun exportToText(id: String): String?

    /** Parses and stores an `.arscript` payload (`fileName` seeds the id). */
    suspend fun importFromText(
        text: String,
        fileName: String? = null,
        overwrite: Boolean = false,
    ): ImportResult

    /** File name suggested for exporting [id]. */
    suspend fun suggestedFileName(id: String): String
}

/** Convenience: the extension used by every stored file. */
internal fun scriptFileName(baseName: String): String = "$baseName${ArScriptConventions.DOT_EXTENSION}"

/** Strips the `.arscript` suffix from a stored file name. */
internal fun scriptBaseName(fileName: String): String =
    fileName.removeSuffix(ArScriptConventions.DOT_EXTENSION).ifBlank { fileName }
