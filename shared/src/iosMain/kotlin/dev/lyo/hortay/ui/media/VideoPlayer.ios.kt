@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * iOS actual for [VideoPlayer] — minimal AVPlayer wrapper. Sufficient for
 * guest-mode video playback (web-mode CDN streams over HTTPS). State flows
 * update from a polling task that ticks while a view is attached; AVPlayer
 * has KVO for `status` / `timeControlStatus` but raw KVO bridging from
 * Kotlin/Native is verbose enough that polling is the cheaper first pass.
 * Upgrade to KVO via NSObjectProtocol callbacks in a follow-up if the polling
 * cadence costs anything measurable.
 *
 * Compared to Android, iOS exposes nothing for first-frame detection out of
 * the box — `AVPlayerLayer.readyForDisplay` is the closest signal; the actual
 * [VideoPlayerView] reads it and latches [firstFrameRendered].
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

    actual var playWhenReady: Boolean
        get() = pendingPlay
        set(value) {
            pendingPlay = value
            if (value) avPlayer.play() else avPlayer.pause()
        }

    actual var repeatModeOne: Boolean
        get() = pendingRepeatOne
        set(value) {
            pendingRepeatOne = value
            // Drive loop manually via NSNotificationCenter (see init).
            avPlayer.actionAtItemEnd = AVPlayerActionAtItemEndNone
        }

    actual var muted: Boolean
        get() = avPlayer.muted
        set(value) {
            avPlayer.muted = value
            _isMuted.value = value
        }

    init {
        // Loop hookup: when the current item plays to end, seek back to zero if
        // repeatModeOne is set, else mark Ended.
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = null,
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

    /** Called from VideoPlayerView's tick to refresh polled state. */
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
        val url = NSURL.URLWithString(uri) ?: return
        val item = AVPlayerItem.playerItemWithURL(url)
        avPlayer.replaceCurrentItemWithPlayerItem(item)
        _firstFrameRendered.value = false
        _videoAspect.value = 0f
        _playbackState.value = PlaybackState.Buffering
        if (pendingPlay) avPlayer.play()
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
        player.avPlayer.pause()
        player.avPlayer.replaceCurrentItemWithPlayerItem(null)
        val pool = if (muted) mutedAvailable else audioAvailable
        if (pool.size < DEFAULT_MAX_SIZE) pool.addLast(player)
    }

    actual fun shutdown() {
        mutedAvailable.clear()
        audioAvailable.clear()
    }

    private companion object { const val DEFAULT_MAX_SIZE = 3 }
}

/**
 * iOS actual for [VideoPlayerView]. Hosts an `AVPlayerLayer` inside a `UIView`
 * via `UIKitView`. The layer's `videoGravity` maps [VideoResizeMode] onto
 * `AVLayerVideoGravity.resizeAspect` / `resizeAspectFill`. First-frame signal
 * is approximated via `AVPlayerLayer.readyForDisplay` (flips shortly after the
 * first frame composites).
 */
@Composable
actual fun VideoPlayerView(
    player: VideoPlayer,
    modifier: Modifier,
    aspectRatio: Float,
    resizeMode: VideoResizeMode,
) {
    UIKitView(
        modifier = modifier,
        factory = {
            val container = UIView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0))
            val layer = AVPlayerLayer.playerLayerWithPlayer(player.avPlayer)
            layer.setFrame(container.bounds)
            layer.videoGravity = when (resizeMode) {
                VideoResizeMode.Fit -> AVLayerVideoGravityResizeAspect
                VideoResizeMode.Zoom -> AVLayerVideoGravityResizeAspectFill
            }
            container.layer.addSublayer(layer)
            container
        },
        update = { container ->
            val layer = container.layer.sublayers?.firstOrNull() as? AVPlayerLayer
            layer?.setFrame(container.bounds)
            // Approximate first-frame: AVPlayerLayer.readyForDisplay flips
            // shortly after the first frame composits to screen.
            if (layer?.readyForDisplay == true) player.markFirstFrame()
            // Refresh polled flows on every recompose tick.
            player.pollSync()
        },
    )
    DisposableEffect(player) {
        onDispose {
            // No explicit detach: AVPlayerLayer holds the AVPlayer reference;
            // when the host view disposes the layer is released and the player
            // returns to its pool via the caller's onDispose.
        }
    }
}
