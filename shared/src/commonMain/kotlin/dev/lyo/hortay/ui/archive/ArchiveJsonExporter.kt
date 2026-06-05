package dev.lyo.hortay.ui.archive

import androidx.compose.runtime.Composable
import okio.BufferedSink

/**
 * Platform JSON exporter for the archive dump. Android wires a SAF
 * `CreateDocument` launcher; iOS is a no-op until file export lands there.
 * Returns a trigger to invoke from a button — once the user picks a
 * destination, [export] is handed a [BufferedSink] to write into.
 */
@Composable
expect fun rememberArchiveJsonExporter(export: suspend (BufferedSink) -> Unit): () -> Unit
