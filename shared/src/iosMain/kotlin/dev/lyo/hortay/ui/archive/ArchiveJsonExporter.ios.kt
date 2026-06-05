package dev.lyo.hortay.ui.archive

import androidx.compose.runtime.Composable
import okio.BufferedSink

/** File export from iOS isn't wired yet — the archive screen's export button is inert there. */
@Composable
actual fun rememberArchiveJsonExporter(export: suspend (BufferedSink) -> Unit): () -> Unit = { }
