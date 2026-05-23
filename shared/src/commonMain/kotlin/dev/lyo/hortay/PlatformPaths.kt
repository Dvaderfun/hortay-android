package dev.lyo.hortay

import okio.Path

/**
 * Per-app writable directory path resolver. Android: `Context.filesDir`. iOS:
 * `NSDocumentDirectory`. Both survive upgrades, are wiped on uninstall, and
 * are private to the app process.
 *
 * [name] is appended to the platform root: `applicationFilesPath("report_log.jsonl")`
 * resolves to `<root>/report_log.jsonl`.
 */
expect fun applicationFilesPath(name: String): Path
