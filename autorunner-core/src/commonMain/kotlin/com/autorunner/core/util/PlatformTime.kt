package com.autorunner.core.util

import kotlin.math.abs
import kotlin.random.Random

/** Wall clock in milliseconds since the Unix epoch. */
expect fun currentTimeMillis(): Long

/** Current instant formatted as ISO-8601 UTC, e.g. `2026-01-15T10:30:00Z`. */
expect fun currentIsoTimestamp(): String

/** Formats an epoch millisecond value as `yyyy-MM-dd`. */
expect fun formatIsoDate(epochMillis: Long): String

/**
 * Renders an ISO-8601 timestamp as a `yyyy-MM-dd` date, robust against
 * hand-edited script files.
 */
fun isoDatePart(isoTimestamp: String): String {
    if (isoTimestamp.length < 10) return isoTimestamp
    return isoTimestamp.substring(0, 10)
}

/** Short, filesystem safe identifier. */
fun randomId(prefix: String = "script"): String {
    val suffix = abs(Random.nextInt()).toString(36).padStart(5, '0').takeLast(6)
    val stamp = currentTimeMillis().toString(36)
    return "$prefix-$stamp-$suffix"
}

/** Strips characters that are unsafe in a file name. */
fun sanitizeFileBaseName(raw: String, fallback: String = "autorunner_script"): String {
    val cleaned = raw.trim()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }
        .joinToString("")
        .trim()
        .replace(' ', '_')
        .trim('_')
    return cleaned.ifBlank { fallback }.take(64)
}
