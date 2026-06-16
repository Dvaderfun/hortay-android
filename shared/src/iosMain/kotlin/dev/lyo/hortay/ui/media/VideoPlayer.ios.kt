@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerActionAtItemEndNone
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.actionAtItemEnd
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol

/**
 * iOS actual for [VideoPlayer] — minimal AVPlayer wrapper. AVPlayer exposes KVO
 * for `status` / `timeControlStatus`, but raw KVO bridging from Kotlin/Native is
 * verbose, so the state flows are refreshed by a polling tick instead — driven
 * by a coroutine in [VideoPlayerView] that calls [pollSync] while a view is
 * attached (NOT by chance recompositions; an earlier cut only ticked inside the
 * UIKitView `update` block, which never re-runs during steady playback, so the
 * whole control surface froze at its mount-time values). Upgrade to KVO via
 * NSObjectProtocol callbacks if the poll cadence ever costs anything measurable.
 *
 * Compared to Android, iOS exposes nothing for first-frame detection out of the
 * box — `AVPlayerLayer.readyForDisplay` is the closest signal; [VideoPlayerView]
 * reads it on each tick and latches [firstFrameRendered].
 */
actual class VideoPlayer internal constructor(
    internal val avPlayer: AVPlayer,
    @Suppress("UNUSED_PARAMETER") mutedAtAcquire: Boolean,
) {
    private val _isPlaying = MutableStateFlow(false)
    private val _playbackState = MutableStateFlow(PlaybackState.Idle)
    private val _durationMs = MutableStateFlow(0L)
    private val _videoAspect = MutableStateFlow(0f)
    private val _firstFrameRendered = MutableStateFlow(false)
    private val _isMuted = MutableStateFlow(avPlayer.muted)

    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    actual val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    actual val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    actual val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()
    actual val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()
    actual val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private var pendingPlay = false
    private var pendingRepeatOne = false

    /** Current source URI, for the no-op-on-same-URI contract (see [setSource]). */
    private var currentUri: String? = null

    /**
     * End-of-item observer token. Scoped to THIS player's current item (not the
     * global `object = null`, which fires for every player and cross-talks the
     * loop/Ended logic) and re-installed per source so it never leaks across a
     * swap; removed in [resetForPool].
     */
    private var endObserver: NSObjectProtocol? = null

    actual var playWhenReady: Boolean
        get() = pendingPlay
        set(value) {
            pendingPlay = value
            if (value) play() else pause()
        }

    actual var repeatModeOne: Boolean
        get() = pendingRepeatOne
        set(value) {
            pendingRepeatOne = value
            // Drive loop manually via NSNotificationCenter (see installEndObserver).
            avPlayer.actionAtItemEnd = AVPlayerActionAtItemEndNone
        }

    actual var muted: Boolean
        get() = avPlayer.muted
        set(value) {
            avPlayer.muted = value
            _isMuted.value = value
        }

    private fun installEndObserver(item: AVPlayerItem) {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (pendingRepeatOne) {
                avPlayer.seekToTime(CMTimeMake(0L, 1))
                if (pendingPlay) avPlayer.play()
            } else {
                _playbackState.value = PlaybackState.Ended
                _isPlaying.value = false
            }
        }
    }

    /** Called from [VideoPlayerView]'s tick to refresh polled state. */
    internal fun pollSync() {
        _isPlaying.value = avPlayer.timeControlStatus == AVPlayerTimeControlStatusPlaying
        _playbackState.value = when (avPlayer.timeControlStatus) {
            AVPlayerTimeControlStatusPlaying -> PlaybackState.Ready
            AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> PlaybackState.Buffering
            AVPlayerTimeControlStatusPaused ->
                if (_playbackState.value == PlaybackState.Ended) PlaybackState.Ended
                else PlaybackState.Ready
            else -> PlaybackState.Idle
        }
        val item = avPlayer.currentItem
        if (item != null) {
            val secs = CMTimeGetSeconds(item.duration)
            if (!secs.isNaN() && secs.isFinite() && secs > 0) {
                _durationMs.value = (secs * 1000).toLong()
            }
        }
    }

    internal fun markFirstFrame() { _firstFrameRendered.value = true }

    actual fun setSource(uri: String) {
        if (uri == currentUri) return // honour the documented no-op-on-same-URI contract
        val url = NSURL.URLWithString(uri) ?: return
        currentUri = uri
        val item = AVPlayerItem.playerItemWithURL(url)
        avPlayer.replaceCurrentItemWithPlayerItem(item)
        installEndObserver(item)
        _firstFrameRendered.value = false
        _videoAspect.value = 0f
        _durationMs.value = 0L
        _playbackState.value = PlaybackState.Buffering
        if (pendingPlay) play()
    }

    actual fun play() {
        pendingPlay = true
        avPlayer.play()
    }

    actual fun pause() {
        pendingPlay = false
        avPlayer.pause()
    }

    actual fun seekTo(positionMs: Long) {
        avPlayer.seekToTime(CMTimeMake(positionMs, 1000))
    }

    actual fun currentPositionMs(): Long {
        val secs = CMTimeGetSeconds(avPlayer.currentTime())
        return if (secs.isNaN() || !secs.isFinite()) 0L else (secs * 1000).toLong()
    }

    /**
     * Reset to a clean state for a pool handback (mirrors Android's). Removes
     * the end observer so a pooled or dropped instance never leaks it, and
     * clears [currentUri] so the next acquire re-loads even the same URI.
     */
    internal fun resetForPool() {
        avPlayer.pause()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        currentUri = null
        pendingPlay = false
        pendingRepeatOne = false
        _isPlaying.value = false
        _playbackState.value = PlaybackState.Idle
        _durationMs.value = 0L
        _videoAspect.value = 0f
        _firstFrameRendered.value = false
    }
}

/**
 * iOS actual for [VideoPlayerPool]. AVPlayer construction is cheap on iOS
 * (no MediaCodec analogue) so the pool is best-effort — sub-pools cap at
 * [DEFAULT_MAX_SIZE] and overflow just allocates fresh instances. The
 * `muted`-flag bookkeeping mirrors Android's API even though AVPlayer's
 * mute is a runtime flag (no audio-renderer split).
 */
actual class VideoPlayerPool {
    private val mutedAvailable = ArrayDeque<VideoPlayer>()
    private val audioAvailable = ArrayDeque<VideoPlayer>()

    actual fun acquire(muted: Boolean): VideoPlayer {
        val pool = if (muted) mutedAvailable else audioAvailable
        return pool.removeFirstOrNull() ?: VideoPlayer(
            avPlayer = AVPlayer().apply { this.muted = muted },
            mutedAtAcquire = muted,
        )
    }

    actual fun release(player: VideoPlayer, muted: Boolean) {
        // resetForPool stops playback, clears the item and removes the end
        // observer — so an overflow instance that isn't pooled is dropped
        // clean (no leaked observer).
        player.resetForPool()
        val pool = if (muted) mutedAvailable else audioAvailable
        if (pool.size < DEFAULT_MAX_SIZE) pool.addLast(player)
    }

    actual fun shutdown() {
        (mutedAvailable + audioAvailable).forEach { it.resetForPool() }
        mutedAvailable.clear()
        audioAvailable.clear()
    }

    private companion object { const val DEFAULT_MAX_SIZE = 3 }
}

/**
 * iOS actual for [VideoPlayerView]. Hosts an `AVPlayerLayer` inside a `UIView`
 * via `UIKitView`. The layer's `videoGravity` maps [VideoResizeMode] onto
 * `AVLayerVideoGravity.resizeAspect` / `resizeAspectFill`.
 *
 * A coroutine pumps [VideoPlayer.pollSync] every [POLL_INTERVAL_MS] while
 * attached — AVPlayer has no push updates wired (KVO deferred), so without it
 * every state flow (isPlaying / playbackState / duration / firstFrame) would
 * only refresh on a chance recomposition of THIS node, which never happens
 * during steady playback. The controls' slider, play/pause glyph, duration
 * label and buffering overlay all read those flows. First-frame is approximated
 * via `AVPlayerLayer.readyForDisplay` on the same tick.
 */
@Composable
actual fun VideoPlayerView(
    player: VideoPlayer,
    modifier: Modifier,
    aspectRatio: Float,
    resizeMode: VideoResizeMode,
) {
    var layer by remember(player) { mutableStateOf<AVPlayerLayer?>(null) }
    UIKitView(
        modifier = modifier,
        factory = {
            val container = UIView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0))
            val playerLayer = AVPlayerLayer.playerLayerWithPlayer(player.avPlayer)
            playerLayer.setFrame(container.bounds)
            playerLayer.videoGravity = when (resizeMode) {
                VideoResizeMode.Fit -> AVLayerVideoGravityResizeAspect
                VideoResizeMode.Zoom -> AVLayerVideoGravityResizeAspectFill
            }
            container.layer.addSublayer(playerLayer)
            layer = playerLayer
            container
        },
        update = { container ->
            (container.layer.sublayers?.firstOrNull() as? AVPlayerLayer)?.setFrame(container.bounds)
        },
    )
    LaunchedEffect(player) {
        while (isActive) {
            player.pollSync()
            if (layer?.readyForDisplay == true) player.markFirstFrame()
            delay(POLL_INTERVAL_MS)
        }
    }
}

// 150 ms keeps the play/pause glyph, duration and buffering overlay visually
// in step without waking the main queue 10×/s; the slider's position has its
// own 100 ms loop in VideoPlayerControls.
private const val POLL_INTERVAL_MS = 150L
