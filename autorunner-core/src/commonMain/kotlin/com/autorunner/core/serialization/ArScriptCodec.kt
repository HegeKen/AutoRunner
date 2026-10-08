package com.autorunner.core.serialization

import com.autorunner.core.model.ScriptModel
import kotlinx.serialization.json.Json

/**
 * The canonical `.arscript` JSON configuration.
 *
 * * `type` is the polymorphic discriminator used by `flow` entries.
 * * unknown keys are ignored so that scripts produced by a newer build keep
 *   loading on an older one.
 * * default values are always written so that a produced file is explicit and
 *   diff friendly.
 */
object ArScriptJson {

    /** Human readable output used when exporting a script to disk. */
    val pretty: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
        classDiscriminator = "type"
    }

    /** Minimal output used for logs, clipboard payloads and IPC. */
    val compact: Json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
        classDiscriminator = "type"
    }
}

/** Raised when a file is not a valid `.arscript` document. */
class ScriptFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Encodes and decodes [ScriptModel] instances.
 *
 * The codec is stateless and therefore safe to share between the UI, the
 * repository and the service layer.
 */
class ArScriptCodec(
    private val pretty: Json = ArScriptJson.pretty,
    private val compact: Json = ArScriptJson.compact,
) {

    /** Serialises [script]; [formatted] controls pretty printing. */
    fun encode(script: ScriptModel, formatted: Boolean = true): String =
        (if (formatted) pretty else compact).encodeToString(ScriptModel.serializer(), script)

    /** Serialises [script], wrapping any failure into a [Result]. */
    fun encodeResult(script: ScriptModel, formatted: Boolean = true): Result<String> =
        runCatching { encode(script, formatted) }

    /**
     * Parses [text].
     *
     * @throws ScriptFormatException when the payload is not a valid script.
     */
    fun decode(text: String): ScriptModel {
        if (text.isBlank()) throw ScriptFormatException("脚本内容为空")
        return try {
            compact.decodeFromString(ScriptModel.serializer(), text)
        } catch (error: Throwable) {
            throw ScriptFormatException("脚本解析失败：${error.message ?: error::class.simpleName}", error)
        }
    }

    /** Parses [text], returning `null` instead of throwing. */
    fun decodeOrNull(text: String): ScriptModel? = runCatching { decode(text) }.getOrNull()

    /** Parses [text], returning a [Result] instead of throwing. */
    fun decodeResult(text: String): Result<ScriptModel> = runCatching { decode(text) }

    /** Looks like an `.arscript` payload without fully parsing it. */
    fun looksLikeScript(text: String): Boolean {
        val head = text.take(4096)
        return head.contains("\"flow\"") || head.contains("\"version\"")
    }
}
