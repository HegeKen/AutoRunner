package com.autorunner.core.serialization

import com.autorunner.core.model.ScriptModel
import kotlinx.serialization.json.Json

/**
 * 规范的 `.arscript` JSON 配置。
 *
 * * `type` 是 `flow` 条目使用的多态判别字段。
 * * 未知键被忽略，这样新版本构建产出的脚本在旧版本上仍能加载。
 * * 默认值总是被写出，使产出的文件内容明确、便于 diff。
 */
object ArScriptJson {

    /** 导出脚本到磁盘时使用的人类可读输出。 */
    val pretty: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
        classDiscriminator = "type"
    }

    /** 用于日志、剪贴板载荷和 IPC 的最小输出。 */
    val compact: Json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
        classDiscriminator = "type"
    }
}

/** 当文件不是合法的 `.arscript` 文档时抛出。 */
class ScriptFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 编解码 [ScriptModel] 实例。
 *
 * 编解码器是无状态的，因此可在 UI、仓库和服务层之间安全共享。
 */
class ArScriptCodec(
    private val pretty: Json = ArScriptJson.pretty,
    private val compact: Json = ArScriptJson.compact,
) {

    /** 序列化 [script]；[formatted] 控制是否美化输出。 */
    fun encode(script: ScriptModel, formatted: Boolean = true): String =
        (if (formatted) pretty else compact).encodeToString(ScriptModel.serializer(), script)

    /** 序列化 [script]，把任何失败包装进 [Result]。 */
    fun encodeResult(script: ScriptModel, formatted: Boolean = true): Result<String> =
        runCatching { encode(script, formatted) }

    /**
     * 解析 [text]。
     *
     * @throws ScriptFormatException 当载荷不是合法脚本时。
     */
    fun decode(text: String): ScriptModel {
        if (text.isBlank()) throw ScriptFormatException("脚本内容为空")
        return try {
            compact.decodeFromString(ScriptModel.serializer(), text)
        } catch (error: Throwable) {
            throw ScriptFormatException("脚本解析失败：${error.message ?: error::class.simpleName}", error)
        }
    }

    /** 解析 [text]，返回 `null` 而不是抛异常。 */
    fun decodeOrNull(text: String): ScriptModel? = runCatching { decode(text) }.getOrNull()

    /** 解析 [text]，返回 [Result] 而不是抛异常。 */
    fun decodeResult(text: String): Result<ScriptModel> = runCatching { decode(text) }

    /** 不做完整解析，仅判断它是否像 `.arscript` 载荷。 */
    fun looksLikeScript(text: String): Boolean {
        val head = text.take(4096)
        // 必须同时命中 flow 与 version：任一 OR 都会误匹配
        // package.json / build.gradle 等含 "version" 字段的普通 JSON。
        return head.contains("\"flow\"") && head.contains("\"version\"")
    }
}
