package dev.lyo.hortay.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS stub for [MediaCache]. Guest-mode UI never asks for a TDLib fileId —
 * the iOS app streams every media payload from `t.me/s/` CDN URLs directly
 * via Coil / AVPlayer, so every slot stays in [MediaState.Idle] forever and
 * the renderer falls through to its own URL path.
 *
 * Phase I will replace this with a real impl once TDLib is cross-compiled
 * for Apple platforms (cinterop bindings + libtdjni.xcframework).
 */
actual class MediaCache {
    private val idleFlow: StateFlow<MediaState> =
        MutableStateFlow<MediaState>(MediaState.Idle).asStateFlow()

    actual fun observe(fileId: Int): StateFlow<MediaState> = idleFlow
    actual suspend fun ensure(fileId: Int, priority: DownloadPriority) {}
    actual fun cancelDeferred(fileId: Int) {}
    actual fun cancelExplicit(fileId: Int) {}
    actual fun resync(fileId: Int) {}
    actual suspend fun retry(fileId: Int, priority: DownloadPriority) {}
    actual fun invalidate(fileId: Int, priority: DownloadPriority) {}
}
