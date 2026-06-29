@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.lyo.hortay.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerActionAtItemEndNone
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.actionAtItemEnd
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBA8
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.NSObjectProtocol

/**
 * iOS actual for [VideoPlayer] — an AVPlayer wrapper that renders through a
 * **manual frame pump**, not a UIKit-hosted AVPlayerLayer/AVPlayerViewController.
 *
 * Why: inside Compose Multiplatform's iOS interop (CMP 1.12-alpha, iOS 27) a
 * hardware-decoded AVPlayer surface never composited — verified exhaustively:
 * AVPlayerLayer as a sublayer AND as a view's backing layer both stayed
 * transparent, and AVPlayerViewController (both interop APIs) played audio with a
 * ReadyToPlay item but showed a black picture. The frames render out-of-process
 * (FigUseVideoReceiverForCALayer) and that IOSurface doesn't blend into CMP's
 * Metal scene. So instead we tap decoded frames via [AVPlayerItemVideoOutput],
 * copy each [CVPixelBufferRef] into a Skia raster, and draw it as an ordinary
 * Compose [Image] — zero interop, so there is no foreign layer to fail to
 * composite. AVPlayer still owns decode, audio, timing and seeking.
 *
 * State flows are refreshed by [pollSync] on the same view-bound tick (AVPlayer's
 * KVO is left unwired — polling is cheaper here).
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

    private var endObserver: NSObjectProtocol? = null

    private var loggedFrame = false // TEMP DIAGNOSTIC (revert)
    private var frameTick = 0L // TEMP DIAGNOSTIC (revert)

    // Frame tap for the manual pump. Created fresh per item in [setSource] — an
    // AVPlayerItemVideoOutput attaches to exactly one item.
    private var videoOutput: AVPlayerItemVideoOutput? = null

    // Decoded frames come back YUV / 10-bit (not BGRA, even when requested), so a
    // raw byte read can't feed Skia. CIContext renders any input format into
    // tightly-packed RGBA8. One context + colour space per player.
    private val ciContext = CIContext()
    private val rgbColorSpace = CGColorSpaceCreateDeviceRGB()

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

    /**
     * Pull the freshest decoded frame as a Compose [ImageBitmap], or null if no new
     * frame is ready since the last call. Runs off the main thread (see
     * [VideoPlayerView]); the copy + raster is the per-frame cost.
     */
    internal fun copyFrameBitmap(): ImageBitmap? {
        val output = videoOutput ?: return null
        val itemTime = output.itemTimeForHostTime(CACurrentMediaTime())
        // Try copy directly (no hasNewPixelBuffer gate, which can starve the first
        // frames during diagnosis).
        val pb = output.copyPixelBufferForItemTime(itemTime, null)
        if (frameTick++ % 30L == 0L) {
            println("[HORTAY] pump tcs=${avPlayer.timeControlStatus} buf=${pb != null} t=${CMTimeGetSeconds(itemTime)}")
        }
        if (pb == null) return null
        val bmp = pixelBufferToBitmap(pb)
        CVPixelBufferRelease(pb) // we own the +1 from copyPixelBuffer…
        return bmp
    }

    private fun pixelBufferToBitmap(pb: CVPixelBufferRef): ImageBitmap? {
        val width = CVPixelBufferGetWidth(pb).toInt()
        val height = CVPixelBufferGetHeight(pb).toInt()
        if (width <= 0 || height <= 0) return null
        _videoAspect.value = width.toFloat() / height.toFloat()
        val rowBytes = width * 4
        val bytes = ByteArray(rowBytes * height)
        // CIContext rasterises the (YUV/HDR) frame into the RGBA8 buffer we own,
        // tightly packed (rowBytes = width×4), so Skia accepts it directly.
        bytes.usePinned { pinned ->
            ciContext.render(
                CIImage.imageWithCVPixelBuffer(pb),
                toBitmap = pinned.addressOf(0),
                rowBytes = rowBytes.toLong(),
                bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                format = kCIFormatRGBA8,
                colorSpace = rgbColorSpace,
            )
        }
        if (!loggedFrame) { loggedFrame = true; println("[HORTAY] first frame ${width}x$height via CIContext") }
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        return SkiaImage.makeRaster(info, bytes, rowBytes).toComposeImageBitmap()
    }

    actual fun setSource(uri: String) {
        if (uri == currentUri) return // honour the documented no-op-on-same-URI contract
        // Local TDLib files MUST go through fileURLWithPath — NSURL.URLWithString on a
        // raw "file://<path>" mis-parses any path with spaces / unicode. Remote (https)
        // web-mode streams use URLWithString.
        val url = if (uri.startsWith("file://")) {
            NSURL.fileURLWithPath(uri.removePrefix("file://"))
        } else {
            NSURL.URLWithString(uri)
        } ?: return
        currentUri = uri
        loggedFrame = false
        val item = AVPlayerItem.playerItemWithURL(url)
        val output = AVPlayerItemVideoOutput(
            pixelBufferAttributes = mapOf<Any?, Any>(
                kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA,
            ),
        )
        item.addOutput(output)
        videoOutput = output
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
     * Reset to a clean state for a pool handback (mirrors Android's). Removes the
     * end observer so a pooled or dropped instance never leaks it, and clears
     * [currentUri] so the next acquire re-loads even the same URI.
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
 * [DEFAULT_MAX_SIZE] and overflow just allocates fresh instances.
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
 * iOS actual for [VideoPlayerView] — a pure-Compose [Image] fed by the manual
 * frame pump (see [VideoPlayer] KDoc for why no UIKit interop is used). A
 * background coroutine ticks [VideoPlayer.pollSync] (state) and
 * [VideoPlayer.copyFrameBitmap] (picture) at [FRAME_INTERVAL_MS]; the latter is a
 * no-op while paused (no new pixel buffer), so the last frame simply persists.
 */
@Composable
actual fun VideoPlayerView(
    player: VideoPlayer,
    modifier: Modifier,
    aspectRatio: Float,
    resizeMode: VideoResizeMode,
) {
    var frame by remember(player) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(player) {
        // Off the main thread: the per-frame pixel copy + Skia raster would jank
        // the UI at video frame rate. Compose snapshot-state writes are safe from
        // a background thread.
        withContext(Dispatchers.Default) {
            while (isActive) {
                player.pollSync()
                player.copyFrameBitmap()?.let {
                    frame = it
                    player.markFirstFrame()
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }
    val f = frame
    if (f != null) {
        Image(
            bitmap = f,
            contentDescription = null,
            modifier = modifier,
            contentScale = if (resizeMode == VideoResizeMode.Zoom) ContentScale.Crop else ContentScale.Fit,
        )
    } else {
        Box(modifier)
    }
}

// ~30 fps. Drives both the picture pump and the state poll; copyFrameBitmap is a
// cheap no-op when there's no new decoded frame (paused / between frames).
private const val FRAME_INTERVAL_MS = 33L
