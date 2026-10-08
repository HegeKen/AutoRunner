package com.autorunner.core.util

import kotlin.math.abs
import kotlin.random.Random

/** Unix 纪元以来的墙钟毫秒数。 */
expect fun currentTimeMillis(): Long

/** 当前时刻格式化为 ISO-8601 UTC，例如 `2026-01-15T10:30:00Z`。 */
expect fun currentIsoTimestamp(): String

/** 把纪元毫秒值格式化为 `yyyy-MM-dd`。 */
expect fun formatIsoDate(epochMillis: Long): String

/**
 * 把 ISO-8601 时间戳渲染为 `yyyy-MM-dd` 日期，对手工编辑过的
 * 脚本文件也能稳健处理。
 */
fun isoDatePart(isoTimestamp: String): String {
    if (isoTimestamp.length < 10) return isoTimestamp
    return isoTimestamp.substring(0, 10)
}

/** 简短且对文件系统安全的标识符。 */
fun randomId(prefix: String = "script"): String {
    val suffix = abs(Random.nextInt()).toString(36).padStart(5, '0').takeLast(6)
    val stamp = currentTimeMillis().toString(36)
    return "$prefix-$stamp-$suffix"
}

/** 去除文件名中不安全的字符。 */
fun sanitizeFileBaseName(raw: String, fallback: String = "autorunner_script"): String {
    val cleaned = raw.trim()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }
        .joinToString("")
        .trim()
        .replace(' ', '_')
        .trim('_')
    return cleaned.ifBlank { fallback }.take(64)
}
