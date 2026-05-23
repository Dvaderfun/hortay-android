package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.lyo.hortay.data.AlbumItem

/**
 * Mounts a single [FullScreenMediaViewer] above [content] and wires it into
 * [LocalMediaViewer] so descendant screens can open media without owning the
 * viewer themselves. The platform Save / Copy / Share routing flows through
 * [LocalMediaShareActions], which is provided by `MainActivity` /
 * `MainViewController`.
 */
@Composable
fun MediaViewerHost(content: @Composable () -> Unit) {
    var state by remember { mutableStateOf<ViewerState?>(null) }
    val controller = remember {
        MediaViewerController { items, idx -> state = ViewerState(items, idx) }
    }
    CompositionLocalProvider(LocalMediaViewer provides controller) {
        content()
    }
    state?.let { vs ->
        FullScreenMediaViewer(
            items = vs.items,
            initialIndex = vs.index,
            onDismiss = { state = null },
        )
    }
}

private data class ViewerState(val items: List<AlbumItem>, val index: Int)
