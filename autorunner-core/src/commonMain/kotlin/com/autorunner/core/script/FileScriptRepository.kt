package com.autorunner.core.script

import com.autorunner.core.model.ArScriptConventions
import com.autorunner.core.model.DeviceInfo
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.serialization.ArScriptCodec
import com.autorunner.core.serialization.ScriptValidator
import com.autorunner.core.storage.ScriptStorage
import com.autorunner.core.storage.createScriptStorage
import com.autorunner.core.util.currentIsoTimestamp
import com.autorunner.core.util.currentTimeMillis
import com.autorunner.core.util.sanitizeFileBaseName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Default [ScriptRepository]: one `.arscript` file per script inside a
 * platform provided directory.
 *
 * Android uses the app private `filesDir/scripts` folder, so no storage
 * permission is ever required. Import/export through the Storage Access
 * Framework is handled by the app module, which simply passes text in and out
 * of this repository.
 */
class FileScriptRepository(
    private val storage: ScriptStorage = createScriptStorage(),
    private val codec: ArScriptCodec = ArScriptCodec(),
    /** Supplies the current device metrics so new scripts get a device block. */
    private val metricsProvider: () -> ScreenMetrics = { ScreenMetrics.Unknown },
    /** Dispatcher used for file IO; injectable so tests stay deterministic. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ScriptRepository {

    private val _scripts = MutableStateFlow<List<ScriptRecord>>(emptyList())

    override val scripts: StateFlow<List<ScriptRecord>> = _scripts.asStateFlow()

    /** Directory the repository writes to; shown in the settings screen. */
    val location: String get() = storage.location

    override suspend fun refresh(): List<ScriptRecord> = withContext(dispatcher) {
        val records = storage.list()
            .asSequence()
            .filter { it.endsWith(ArScriptConventions.DOT_EXTENSION) }
            .mapNotNull { readRecord(it) }
            .sortedByDescending { it.updatedAtMs }
            .toList()
        _scripts.value = records
        records
    }

    override suspend fun load(id: String): ScriptRecord? = withContext(dispatcher) {
        _scripts.value.firstOrNull { it.id == id } ?: readRecord(scriptFileName(id))
    }

    override suspend fun save(id: String?, script: ScriptModel): ScriptRecord = withContext(dispatcher) {
        val existing = id?.let { current -> _scripts.value.firstOrNull { it.id == current } }
        val baseName = existing?.id ?: uniqueBaseName(script.displayName())
        val fileName = existing?.fileName ?: scriptFileName(baseName)
        val normalised = stamp(script)
        val payload = codec.encode(normalised)
        storage.write(fileName, payload)

        val record = ScriptRecord(
            id = baseName,
            fileName = fileName,
            script = normalised,
            updatedAtMs = storage.lastModified(fileName).takeIf { it > 0L } ?: currentTimeMillis(),
            sizeBytes = payload.encodeToByteArray().size,
        )
        upsert(record)
        record
    }

    override suspend fun rename(id: String, newName: String): ScriptRecord? {
        val record = load(id) ?: return null
        val trimmed = newName.trim().ifBlank { return null }
        return save(id, record.script.copy(info = record.script.info.copy(name = trimmed)))
    }

    override suspend fun duplicate(id: String): ScriptRecord? {
        val record = load(id) ?: return null
        val copy = record.script.copy(
            info = record.script.info.copy(name = "${record.name} 副本", createdAt = currentIsoTimestamp()),
        )
        return save(null, copy)
    }

    override suspend fun delete(id: String): Boolean = withContext(dispatcher) {
        val record = _scripts.value.firstOrNull { it.id == id }
        val removed = storage.delete(record?.fileName ?: scriptFileName(id))
        if (removed) {
            _scripts.value = _scripts.value.filterNot { it.id == id }
        }
        removed
    }

    override suspend fun deleteAll(): Int = withContext(dispatcher) {
        val count = _scripts.value.size
        storage.clear()
        _scripts.value = emptyList()
        count
    }

    override suspend fun exportToText(id: String): String? = withContext(dispatcher) {
        val record = load(id) ?: return@withContext null
        codec.encode(record.script, formatted = true)
    }

    override suspend fun importFromText(
        text: String,
        fileName: String?,
        overwrite: Boolean,
    ): ImportResult = withContext(dispatcher) {
        val decoded = codec.decodeResult(text).getOrElse { error ->
            return@withContext ImportResult.Failure(error.message ?: "无法解析脚本文件")
        }

        val validation = ScriptValidator.validate(decoded)
        if (!validation.isValid) {
            val detail = validation.errors.firstOrNull()?.message ?: "脚本内容不合法"
            return@withContext ImportResult.Failure(detail)
        }

        val requestedBase = sanitizeFileBaseName(
            fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: decoded.displayName(),
        )
        val baseName = if (overwrite && storage.exists(scriptFileName(requestedBase))) {
            requestedBase
        } else {
            uniqueBaseName(requestedBase)
        }

        val prepared = stamp(
            decoded.copy(
                info = decoded.info.copy(
                    name = decoded.info.name.ifBlank { requestedBase },
                    createdAt = currentIsoTimestamp(),
                ),
            ),
        )
        val storedName = scriptFileName(baseName)
        val payload = codec.encode(prepared)
        storage.write(storedName, payload)

        val record = ScriptRecord(
            id = baseName,
            fileName = storedName,
            script = prepared,
            updatedAtMs = currentTimeMillis(),
            sizeBytes = payload.encodeToByteArray().size,
        )
        upsert(record)
        ImportResult.Success(record, validation.warnings.map { it.message })
    }

    override suspend fun suggestedFileName(id: String): String {
        val record = load(id)
        return ArScriptConventions.fileNameFor(record?.name ?: id)
    }

    // ------------------------------------------------------------------ utils

    private fun readRecord(fileName: String): ScriptRecord? {
        val text = storage.read(fileName) ?: return null
        val script = codec.decodeOrNull(text) ?: return null
        return ScriptRecord(
            id = scriptBaseName(fileName),
            fileName = fileName,
            script = script,
            updatedAtMs = storage.lastModified(fileName),
            sizeBytes = text.encodeToByteArray().size,
        )
    }

    private fun upsert(record: ScriptRecord) {
        val others = _scripts.value.filterNot { it.id == record.id }
        _scripts.value = (others + record).sortedByDescending { it.updatedAtMs }
    }

    /** Fills `createdAt` / device block when the payload does not carry them. */
    private fun stamp(script: ScriptModel): ScriptModel {
        val metrics = metricsProvider()
        val device = script.info.device
        val patchedDevice = if (device.width <= 0 || device.height <= 0) {
            if (metrics.isValid) DeviceInfo.of(metrics) else device
        } else {
            device
        }
        val name = script.info.name.ifBlank { "未命名脚本 ${currentIsoTimestamp().take(16).replace('T', ' ')}" }
        return script.copy(
            info = script.info.copy(
                name = name,
                device = patchedDevice,
                createdAt = script.info.createdAt.ifBlank { currentIsoTimestamp() },
            ),
        )
    }

    private fun uniqueBaseName(rawName: String): String {
        val base = sanitizeFileBaseName(rawName)
        val taken = _scripts.value.map { it.id }.toSet() + storage.list().map { scriptBaseName(it) }
        if (base !in taken) return base
        var index = 2
        while ("$base-$index" in taken) index++
        return "$base-$index"
    }
}
