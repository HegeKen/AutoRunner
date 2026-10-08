package com.autorunner.core.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun currentIsoTimestamp(): String =
    Instant.ofEpochMilli(System.currentTimeMillis()).truncatedTo(ChronoUnit.SECONDS).toString()

/** UTC based, consistent with [currentIsoTimestamp]. */
actual fun formatIsoDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
