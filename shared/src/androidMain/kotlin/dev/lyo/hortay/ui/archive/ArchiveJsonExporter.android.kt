package dev.lyo.hortay.ui.archive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.BufferedSink
import okio.buffer
import okio.sink

@Composable
actual fun rememberArchiveJsonExporter(export: suspend (BufferedSink) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.sink().buffer().use { sink -> export(sink) }
                }
            }
        }
    }
    return { launcher.launch("hortay-archive.json") }
}
