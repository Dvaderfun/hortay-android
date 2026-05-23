package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.PostContent

/**
 * Single full-screen media viewer accessor for the whole app. Mounted once at
 * the top of the UI tree (see `MediaViewerHost`); any screen — timeline,
 * comments, channel detail — opens media via
 * `LocalMediaViewer.current.openFor`. Centralising the affordance avoids
 * each screen owning a private `viewerState` and a duplicated
 * `FullScreenMediaViewer` composable.
 */
val LocalMediaViewer = staticCompositionLocalOf<MediaViewerController> {
    error("MediaViewerHost is missing — wrap your UI tree in MediaViewerHost { … }")
}

/**
 * Open-side handle returned by `MediaViewerHost`. Hides the host's
 * `MutableState<ViewerState?>` from call sites so screens only see the
 * affordance, not the storage.
 */
class MediaViewerController internal constructor(
    private val opener: (List<AlbumItem>, Int) -> Unit,
) {
    fun open(items: List<AlbumItem>, index: Int = 0) {
        if (items.isEmpty()) return
        opener(items, index)
    }

    /** Convenience: project [PostContent] and open if it has any viewable media. */
    fun openFor(content: PostContent, index: Int = 0) {
        content.toAlbumItems()?.let { open(it, index) }
    }
}
