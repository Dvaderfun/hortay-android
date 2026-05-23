package dev.lyo.hortay

import kotlin.time.Clock
import kotlin.time.Instant

/** Wall-clock milliseconds since the Unix epoch. KMP replacement for `System.currentTimeMillis()`. */
@OptIn(kotlin.time.ExperimentalTime::class)
fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Parse an ISO-8601 timestamp with offset (e.g. "2026-04-12T18:43:00+00:00") to epoch millis. */
@OptIn(kotlin.time.ExperimentalTime::class)
fun parseIsoToEpochMs(iso: String): Long = runCatching {
    Instant.parse(iso).toEpochMilliseconds()
}.getOrElse { 0L }
