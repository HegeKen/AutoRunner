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
 * 默认的 [ScriptRepository]：在平台提供的目录内，每个脚本对应一个
 * `.arscript` 文件。
 *
 * Android 使用应用私有的 `filesDir/scripts` 目录，因此永远不需要存储
 * 权限。通过存储访问框架（Storage Access Framework）的导入／导出由应用
 * 模块处理，它只是把文本进出传递给本仓库。
 */
class FileScriptRepository(
    private val storage: ScriptStorage = createScriptStorage(),
    private val codec: ArScriptCodec = ArScriptCodec(),
    /** 提供当前设备指标，让新脚本带上设备信息块。 */
    private val metricsProvider: () -> ScreenMetrics = { ScreenMetrics.Unknown },
    /** 文件 IO 使用的调度器；可注入以保证测试的确定性。 */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ScriptRepository {

    private val _scripts = MutableStateFlow<List<ScriptRecord>>(emptyList())

    override val scripts: StateFlow<List<ScriptRecord>> = _scripts.asStateFlow()

    /** 仓库写入的目录；会显示在设置页。 */
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

    /** 当载荷缺少 `createdAt` / 设备信息块时补齐它们。 */
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
        // 直接取已有同名后缀的最大值 +1，避免逐个递增探测（同名堆积时逐 probe 是 O(n²)）。
        val maxSuffix = taken
            .mapNotNull { name ->
                name.removePrefix("$base-").takeIf { name.startsWith("$base-") }?.toIntOrNull()
            }
            .maxOrNull() ?: 1
        return "$base-${maxSuffix + 1}"
    }
}
