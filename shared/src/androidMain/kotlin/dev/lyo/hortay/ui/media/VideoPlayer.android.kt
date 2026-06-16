package dev.lyo.hortay.ui.media

import android.content.Context
import android.os.Handler
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.AspectRatioFrameLayout
import dev.lyo.hortay.data.PlatformContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Android actual for [VideoPlayer] — thin wrapper over ExoPlayer. State flows
 * are driven by a single [Player.Listener] attached at construction; the
 * underlying `Player` is owned by [VideoPlayerPool] (this class never calls
 * `release()` on its own — only the pool's `shutdown` path does).
 */
actual class VideoPlayer internal constructor(
    internal val exo: ExoPlayer,
    private val mutedAtAcquire: Boolean,
) {
    private val _isPlaying = MutableStateFlow(exo.isPlaying)
    private val _playbackState = MutableStateFlow(mapState(exo.playbackState))
    private val _durationMs = MutableStateFlow(exo.duration.coerceAtLeast(0L))
    private val _videoAspect = MutableStateFlow(0f)
    private val _firstFrameRendered = MutableStateFlow(false)
    private val _isMuted = MutableStateFlow(exo.volume == 0f)

    /** Current source URI, for the no-op-on-same-URI contract (see [setSource]). */
    private var currentUri: String? = null

    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    actual val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    actual val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    actual val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()
    actual val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()
    actual val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    actual var playWhenReady: Boolean
        get() = exo.playWhenReady
        set(value) { exo.playWhenReady = value }

    actual var repeatModeOne: Boolean
        get() = exo.repeatMode == Player.REPEAT_MODE_ONE
        set(value) {
            exo.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    actual var muted: Boolean
        get() = exo.volume == 0f
        // For muted-acquired players the underlying renderer set has no audio
        // path — setting volume back to 1f is a silent no-op (audible silence).
        // Audio toggle mid-clip should re-acquire through the unmuted sub-pool;
        // this setter still tracks the flag so the chrome reflects intent.
        set(value) { exo.volume = if (value) 0f else 1f }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
        override fun onPlaybackStateChanged(playbackState: Int) {
            _playbackState.value = mapState(playbackState)
            if (playbackState == Player.STATE_READY) {
                _durationMs.value = exo.duration.coerceAtLeast(0L)
            }
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                val pixelRatio = if (videoSize.pixelWidthHeightRatio > 0f) {
                    videoSize.pixelWidthHeightRatio
                } else 1f
                _videoAspect.value = (videoSize.width * pixelRatio) / videoSize.height
            }
        }
        override fun onRenderedFirstFrame() { _firstFrameRendered.value = true }
        override fun onVolumeChanged(volume: Float) { _isMuted.value = volume == 0f }
    }

    init { exo.addListener(listener) }

    actual fun setSource(uri: String) {
        // Honour the documented no-op-on-same-URI contract: TdVideoPlayer's
        // source-swap effect re-fires on every MediaState re-emission, and a
        // re-setMediaItem+prepare on the SAME path tears down and re-prepares
        // the decoder — a visible rebuffer flash mid-watch.
        if (uri == currentUri) return
        currentUri = uri
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        // Reset the per-source latches so a sticker swap can re-gate its thumb
        // on the new source's first frame, and a quality switch re-letterboxes
        // when the new format reports a different aspect.
        _firstFrameRendered.value = false
        _videoAspect.value = 0f
    }

    actual fun play() { exo.play() }
    actual fun pause() { exo.pause() }
    actual fun seekTo(positionMs: Long) { exo.seekTo(positionMs) }
    actual fun currentPositionMs(): Long = exo.currentPosition.coerceAtLeast(0L)

    /** Called by the pool on release / shutdown — detaches the listener. */
    internal fun detachListener() { exo.removeListener(listener) }

    /** Reset state for a clean handback to the pool. */
    internal fun resetForPool() {
        exo.stop()
        exo.clearMediaItems()
        currentUri = null
        exo.playWhenReady = false
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.volume = if (mutedAtAcquire) 0f else 1f
        _firstFrameRendered.value = false
        _videoAspect.value = 0f
        _durationMs.value = 0L
        _isMuted.value = exo.volume == 0f
        _isPlaying.value = false
        _playbackState.value = PlaybackState.Idle
    }

    private fun mapState(state: Int): PlaybackState = when (state) {
        Player.STATE_IDLE -> PlaybackState.Idle
        Player.STATE_BUFFERING -> PlaybackState.Buffering
        Player.STATE_READY -> PlaybackState.Ready
        Player.STATE_ENDED -> PlaybackState.Ended
        else -> PlaybackState.Idle
    }
}

/**
 * Android actual for [VideoPlayerPool]. Context resolved from
 * [PlatformContextHolder] so commonMain consumers don't need a Context handle.
 *
 * See the [VideoPlayer] / [VideoPlayerPool] common KDocs for the two-sub-pool
 * rationale (muted players are built without an audio renderer entirely;
 * mixing regimes re-arms AudioMix for users who don't need it).
 */
actual class VideoPlayerPool {
    private val context: Context = PlatformContextHolder.require().applicationContext
    private val maxSize: Int = DEFAULT_MAX_SIZE
    private val mutedAvailable = ArrayDeque<VideoPlayer>(maxSize)
    private val audioAvailable = ArrayDeque<VideoPlayer>(maxSize)

    @Synchronized
    actual fun acquire(muted: Boolean): VideoPlayer {
        val pool = if (muted) mutedAvailable else audioAvailable
        return pool.pollFirst() ?: VideoPlayer(build(muted), mutedAtAcquire = muted)
    }

    @Synchronized
    actual fun release(player: VideoPlayer, muted: Boolean) {
        player.resetForPool()
        val pool = if (muted) mutedAvailable else audioAvailable
        if (pool.size < maxSize) {
            pool.offerLast(player)
        } else {
            player.detachListener()
            player.exo.release()
        }
    }

    @Synchronized
    actual fun shutdown() {
        sequenceOf(mutedAvailable, audioAvailable).forEach { queue ->
            while (true) {
                val p = queue.pollFirst() ?: break
                p.detachListener()
                p.exo.release()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun build(muted: Boolean): ExoPlayer {
        val builder = ExoPlayer.Builder(context).apply {
            if (muted) setRenderersFactory(VideoOnlyRenderersFactory(context))
            // WAKE_MODE_NONE: playback never holds a wakelock during the brief
            // window between play() and pause(); Compose disposal already pauses
            // on ON_PAUSE.
            setWakeMode(C.WAKE_MODE_NONE)
        }
        return builder.build()
    }

    private companion object {
        // Sized for a typical timeline: one foreground (fullscreen viewer or
        // autoplay dead-centre), one previous, one next. Beyond that the user
        // is scrolling fast enough that off-viewport players never render a
        // frame anyway — build+teardown on overflow is cheaper than holding
        // more idle instances.
        const val DEFAULT_MAX_SIZE = 3
    }
}

/**
 * [DefaultRenderersFactory] that skips audio renderers entirely. Used for
 * muted sub-pool builds — no AudioTrack, no AudioMix system wakelock.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class VideoOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // intentionally empty — see common VideoPlayerPool KDoc for rationale.
    }
}

/**
 * Android actual for [VideoPlayerView] — bare `TextureView` inside an
 * `AspectRatioFrameLayout` (the same primitive media3's `PlayerView` used
 * internally with `RESIZE_MODE_FIT`). TextureView with `isOpaque = false`
 * blends transparently until the texture is populated, so any underlying
 * blurred-poster composable reads through during the prepare/buffer window
 * (kills the 1-3s black-square preroll a `SurfaceView`-backed `PlayerView`
 * produced); it's also required for WebM stickers' real alpha channel to
 * composite against the post card instead of punching through to the window
 * background.
 */
@Composable
actual fun VideoPlayerView(
    player: VideoPlayer,
    modifier: Modifier,
    aspectRatio: Float,
    resizeMode: VideoResizeMode,
) {
    var liveAspect by remember(player) { mutableStateOf(aspectRatio) }
    LaunchedEffect(player) {
        player.videoAspect.collect { reported ->
            if (reported > 0f) liveAspect = reported
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val texture = TextureView(ctx).apply { isOpaque = false }
            player.exo.setVideoTextureView(texture)
            AspectRatioFrameLayout(ctx).apply {
                this.resizeMode = when (resizeMode) {
                    VideoResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                if (aspectRatio > 0f) setAspectRatio(aspectRatio)
                addView(texture)
            }
        },
        update = { frame ->
            if (liveAspect > 0f) frame.setAspectRatio(liveAspect)
        },
        onRelease = { frame ->
            (frame.getChildAt(0) as? TextureView)?.let { player.exo.clearVideoTextureView(it) }
        },
    )
}
