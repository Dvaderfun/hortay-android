package dev.lyo.hortay.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-bridged TDLib file-download cache. The Android actual is the heavy
 * concrete impl that owns the watchdog, the stalled-download reissue, the
 * priority queue, and the [MediaState] reducer. The iOS actual is a stub —
 * guest-mode UI never asks for a TDLib fileId (those slots stream from
 * `t.me/s/` CDN URLs directly), so every method either returns an empty flow
 * or no-ops.
 *
 * Public surface intentionally minimal — only the methods commonMain UI
 * (MediaBinding, TdMediaImage, MediaProgressIndicator, full-screen viewer)
 * actually calls.
 */
expect class MediaCache {
    /**
     * Subscribe to the live [MediaState] for [fileId]. The same flow handle is
     * shared between all observers of the same fileId, so an arbitrary number
     * of UI nodes can collect without each owning a download lifecycle.
     */
    fun observe(fileId: Int): StateFlow<MediaState>

    /**
     * Ensure [fileId] is downloading at (or above) [priority]. Idempotent —
     * a subsequent call with a higher priority promotes the in-flight job;
     * a lower priority is a no-op. Routes around the dispose-debounce so a
     * mounting card that lost the race with `cancelDeferred` is restored
     * before TDLib actually cancels.
     */
    suspend fun ensure(fileId: Int, priority: DownloadPriority)

    /**
     * Schedule a cancellation with a short debounce window. The scroll-gate
     * fires this on every scroll-past; if the card re-mounts within the
     * window (typical for fast back-scroll), [ensure] revives it without
     * a TDLib round-trip.
     */
    fun cancelDeferred(fileId: Int)

    /**
     * Force the slot to [MediaState.Idle] immediately, bypassing the debounce
     * window and observer-count guard. Call from tap-to-cancel affordances
     * on loading overlays.
     */
    fun cancelExplicit(fileId: Int)

    /**
     * Defensive resync — asks TDLib "what is this file, really?" via
     * `GetFile`. Closes the "shows not loaded until I scroll away and back"
     * class of bugs caused by lost UpdateFile events.
     */
    fun resync(fileId: Int)

    /**
     * Retry a [MediaState.Failed] slot. Suspends until the new ensure() is
     * accepted; the resulting state transition arrives via [observe].
     */
    suspend fun retry(fileId: Int, priority: DownloadPriority)

    /**
     * Invalidate a [MediaState.Ready] slot whose on-disk file has vanished
     * (TDLib's storage optimiser silently evicted it — tdlib/td#3178). The
     * slot reverts to Idle and the next [ensure] re-downloads.
     */
    fun invalidate(fileId: Int, priority: DownloadPriority)
}
