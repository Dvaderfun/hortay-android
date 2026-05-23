package dev.lyo.hortay.data

/**
 * Download tier — caller intent passed to [MediaCache.ensure]. Higher value =
 * higher priority for the TDLib download scheduler.
 */
enum class DownloadPriority(val tdValue: Int) {
    /** Active full-screen viewer / playing video. */
    Foreground(32),

    /**
     * Card whose centre is closest to the viewport centre — i.e. what the user is
     * most likely staring at right now. Sits one tier above plain [VisibleMedia]
     * so on a tight TDLib pool (mobile/roaming, ~4 active slots per channel) the
     * dominant card always grabs a slot first regardless of LIFO ordering inside
     * lane 16.
     */
    VisibleCenter(24),

    /** Photo / video thumb currently visible in the timeline (off-centre). */
    VisibleMedia(16),

    /** Off-screen but next-up — speculative prefetch. */
    Prefetch(8),

    /** Avatar small (160×160). Always loses to media. */
    Avatar(2),
}

/**
 * State of a single [MediaCache] entry. Emitted as a `StateFlow<MediaState>` per
 * TDLib `fileId` so any number of UI consumers can observe the same download
 * without each owning its own lifecycle.
 */
sealed interface MediaState {
    data object Idle : MediaState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    ) : MediaState
    data class Ready(val path: String) : MediaState
    data class Failed(val reason: String) : MediaState
}
