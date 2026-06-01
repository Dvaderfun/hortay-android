package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.TdMedia

/**
 * Plays a Telegram WebM (VP9) sticker. Looped, muted, no controls — the typical
 * "video sticker" UX. Built on a pooled [VideoPlayer] because video decoders
 * are scarce (most devices ship 2-4 VP9 decoders) and the pool manages decoder
 * reuse internally.
 *
 * Renders into [VideoPlayerView]'s underlying TextureView (NOT
 * `PlayerView`/SurfaceView). Telegram's WebM stickers carry a real alpha
 * channel — transparent backgrounds — and SurfaceView is a hardware overlay
 * that renders in its own window layer behind the app. Transparent regions
 * would "punch through" the app window and reveal whatever the OS shows
 * underneath, which on a dark theme reads as a black square. TextureView
 * renders into the app's normal GL-backed canvas, so alpha composites
 * correctly with the surrounding content. The view's `isOpaque = false` is
 * the bit that flips that behaviour on.
 *
 * Lifecycle: paused on ON_PAUSE, resumed on ON_RESUME, fully released on
 * dispose. The lazy-list dispose is what stops the decoder when the sticker
 * scrolls off-screen — Compose tears the composable down and the
 * [DisposableEffect] frees the player back to the pool.
 *
 * The static thumbnail underlays the player until the FIRST FRAME has actually
 * been rendered. Gating on `mediaState is Ready` (the older condition) is
 * insufficient because (a) guest mode streams directly from a URL with no
 * MediaCache state at all, and (b) even in TDLib mode `Ready` fires when
 * bytes land, well before the first video frame is decoded.
 * [VideoPlayer.firstFrameRendered] is the precise boundary: a true frame is
 * on-texture and any thumb hide afterwards is safe.
 *
 * Looping: we deliberately do NOT use [VideoPlayer.repeatModeOne] here. The
 * internal auto-loop path in media3 1.10 re-prepares the same MediaItem via
 * its cached extractor state, and for short Telegram WebM stickers (~1-3s,
 * sparse keyframes, Cues element often absent) the re-prepare reuses the tail
 * of the previous read instead of landing on position 0 cleanly. Observable
 * symptom: first cycle plays in full, every subsequent cycle plays only the
 * last fragment over and over. Manually driving the loop via the
 * [VideoPlayer.playbackState] flow → [VideoPlayer.seekTo] sidesteps the
 * internal path entirely — a fresh seek-to-0 is always keyframe-aligned,
 * costs one main-thread callback every ~2s (negligible), and the same fix
 * doesn't need to propagate to [TdVideoPlayer] /
 * [dev.lyo.hortay.ui.timeline.VideoNotePlayerBubble] because they play MP4/H.264
 * with proper indexes where the internal auto-loop is well-behaved.
 */
@Composable
fun WebmStickerPlayer(
    fileId: Int?,
    thumb: TdMedia?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
    /**
     * Web-mode WebM URL. When [fileId] is null and [remoteUrl] is set, the
     * player streams the URL directly via its built-in HTTP DataSource — no
     * [dev.lyo.hortay.data.MediaCache] interaction. Same path that
     * [TdVideoPlayer] uses for guest-mode video posts, kept consistent so a
     * future single-source-of-truth refactor merges easily.
     */
    remoteUrl: String? = null,
) {
    val pool = LocalVideoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRemote = fileId == null && remoteUrl != null

    // Centralised observe / ensure / cancelDeferred — see [rememberMediaBinding].
    val binding = rememberMediaBinding(fileId = fileId, priority = priority, isRemote = isRemote)

    // Acquire from the shared pool. WebM stickers are inherently silent, so we
    // request the muted variant — no audio renderer is built, AudioTrack is
    // never allocated, and the AudioMix system wakelock is never taken.
    val player = remember {
        pool.acquire(muted = true).apply {
            playWhenReady = true
            // Loop is driven manually below — see class KDoc for why repeatModeOne
            // is unsafe for short Telegram WebM stickers in media3 1.10.
            repeatModeOne = false
            muted = true
        }
    }

    // Key on the Ready path only so Downloading-burst progress updates don't churn
    // the player's setSource cycle. fileId / remoteUrl are captured so swapping
    // to a different sticker instance triggers a fresh prepare even if the new
    // file's path coincidentally matches the previous one.
    val readyPath = binding.readyPath
    LaunchedEffect(readyPath, fileId, remoteUrl, isRemote) {
        val uri: String = when {
            // K2 smart-casts remoteUrl to String via the isRemote val above
            // (`fileId == null && remoteUrl != null`).
            isRemote -> remoteUrl
            else -> {
                val path = readyPath ?: return@LaunchedEffect
                "file://$path"
            }
        }
        player.setSource(uri)
    }

    val firstFrameRendered by player.firstFrameRendered.collectAsStateWithLifecycle()

    // Manual loop: when the player reports Ended, seek back to 0 to restart.
    // See class KDoc for why we don't use repeatModeOne for WebM stickers.
    val playbackState by player.playbackState.collectAsStateWithLifecycle()
    LaunchedEffect(playbackState) {
        if (playbackState == PlaybackState.Ended) player.seekTo(0L)
    }

    DisposableEffect(player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pool.release(player, muted = true)
        }
    }

    // TRANSPARENT-content reveal (see [MediaReveal] KDoc): the player sits underneath and
    // the static thumb cross-dissolves OUT on top once a real frame lands, rather than
    // hard-cutting on `firstFrameRendered`. The thumb must fade (not hold full alpha) or a
    // playing frame under a static thumb would show a doubled silhouette. Kept composed
    // through the fade by [rememberPlaceholderLinger], keyed on the sticker identity.
    val revealed = firstFrameRendered
    val revealAlpha = rememberRevealAlpha(revealed)
    val showThumb = rememberPlaceholderLinger(revealed, key = fileId ?: remoteUrl)
    Box(modifier = modifier) {
        VideoPlayerView(
            player = player,
            modifier = Modifier.fillMaxSize(),
            resizeMode = VideoResizeMode.Fit,
        )
        if (thumb != null && showThumb) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - revealAlpha }) {
                TdMediaImage(
                    media = thumb,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderColor = null,
                    showProgress = false,
                    priority = priority,
                )
            }
        }
    }
}
