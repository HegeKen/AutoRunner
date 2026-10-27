package com.autorunner.core.serialization

import com.autorunner.core.model.ComboBundle
import com.autorunner.core.util.sanitizeFileBaseName

/**
 * `.arcombo` 组合任务文件的编解码。
 *
 * 复用 [ArScriptJson]：`type` 判别字段与 `.arscript` 完全一致，脚本原文可以
 * 直接嵌在 `scripts[].script` 里；未知字段容忍，默认值写出。
 */
object ComboBundleCodec {

    fun encode(bundle: ComboBundle): String =
        ArScriptJson.pretty.encodeToString(ComboBundle.serializer(), bundle)

    /**
     * 解析组合任务文件；格式标识 / 版本不匹配或 JSON 损坏时返回 `null`。
     */
    fun decode(text: String): ComboBundle? {
        if (text.isBlank()) return null
        val bundle = runCatching {
            ArScriptJson.compact.decodeFromString(ComboBundle.serializer(), text)
        }.getOrNull() ?: return null
        if (bundle.format != ComboBundle.FORMAT) return null
        if (bundle.version !in 1..ComboBundle.VERSION) return null
        return bundle
    }

    /** 导出时建议的文件名：`A_B.arcombo`。 */
    fun fileNameFor(bundle: ComboBundle): String {
        val names = bundle.scripts
            .map { it.script.displayName() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
        val base = if (names.isEmpty()) {
            "autorunner_combo"
        } else {
            sanitizeFileBaseName(names.joinToString("_"), fallback = "autorunner_combo")
        }
        return "$base${ComboBundle.DOT_EXTENSION}"
    }
}
