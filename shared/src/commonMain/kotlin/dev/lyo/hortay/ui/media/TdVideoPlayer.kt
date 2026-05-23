package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaState
import kotlinx.coroutines.launch

/**
 * Plays a TDLib-managed video. Asks [dev.lyo.hortay.data.MediaCache] to download the
 * file (deduplicated and priority-aware) and once it lands on disk, hands the path
 * to a pooled [VideoPlayer]. While the download is in flight a [MediaIndeterminateIndicator]
 * is shown over a transparent surface; underlying composables (typically the
 * blurred poster from [TdMediaImage]) remain visible.
 *
 * Lifecycle:
 *   • Tied to the host's [Lifecycle]: pauses on STOP, releases on DESTROY.
 *   • [autoLoop] = true → silent looping (Telegram "GIF" animations).
 *
 * Render path: [VideoPlayerView] wraps a bare [android.view.TextureView] inside an
 * `AspectRatioFrameLayout` for every call site, fullscreen and feed-preview
 * alike. An earlier iteration used media3's `PlayerView` (with
 * `useController=true`) for the fullscreen path to get its built-in scrubber
 * widgets; that surface ships stock 2010s system styling that clashed with the
 * rest of the app's M3 Expressive vocabulary (polygon shapes, wavy progress,
 * motion-token transitions). Replaced by [VideoPlayerControls], a Compose
 * chrome painted over the same surface. Two render-path benefits fall out for
 * free:
 *   • The transparent `TextureView` continues to read through to the
 *     underlying poster while the player prepares (kills the 2-3s black-square
 *     preroll a `SurfaceView`-backed `PlayerView` produced).
 *   • Quality / mute / playback / seek state is now a pure Compose concern,
 *     so the chrome composes through the same `@Immutable` stability chain as
 *     the rest of the UI instead of hiding mutation behind an `AndroidView`.
 *
 * Quality switch: the caller changes [fileId]. [dev.lyo.hortay.data.MediaCache]
 * is asked to download the new file; once it's Ready, the player's source is
 * swapped while preserving the current playback position so the user resumes
 * mid-frame instead of restarting.
 */
@Composable
fun TdVideoPlayer(
    fileId: Int,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    autoLoop: Boolean = false,
    showControls: Boolean = true,
    muted: Boolean = false,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
    /**
     * Remote-URL fallback used by web (anonymous) mode. When [fileId] is 0
     * (placeholder for "no TDLib file") and [remoteUrl] is set, the player
     * streams the URL directly via its built-in HTTP DataSource — bypassing
     * the [dev.lyo.hortay.data.MediaCache] download orchestration that has
     * nothing to do here. Lets PostBody's video block render guest-mode videos
     * through the same Composable that TDLib mode uses.
     */
    remoteUrl: String? = null,
    /**
     * Pre-seed for the letterbox aspect. 0f means "unknown — fill parent until
     * the decoder reports the real size". A non-zero value (Telegram's poster
     * width / height) letterboxes the texture correctly on first layout,
     * eliminating the one-frame "stretch then snap" the fullscreen viewer
     * otherwise showed between mount and the player's first `videoAspect`
     * update. Once the decoder reports the real aspect (typically identical to
     * the poster) we adopt that authoritatively; a mismatched seed only costs
     * one re-layout, not a re-decode.
     */
    initialAspect: Float = 0f,
) {
    val pool = LocalVideoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coScope = rememberCoroutineScope()
    val isRemote = fileId == 0 && remoteUrl != null

    // Centralised observe / ensure / cancelDeferred — see [rememberMediaBinding].
    // Web-mode (isRemote) makes the binding a no-op shape: the player streams
    // direct from [remoteUrl] via its built-in HTTP DataSource and the
    // download orchestration has nothing to do here. [fileId] of `0` is the
    // historical "no TDLib file" sentinel for this composable; pass null to
    // the binding so it stays in [MediaState.Idle] regardless of whether
    // [isRemote] is true or false.
    val binding = rememberMediaBinding(
        fileId = if (fileId == 0) null else fileId,
        priority = priority,
        isRemote = isRemote,
    )
    val mediaState = binding.state
    val showLoadingOverlay = rememberDeferredLoading(state = mediaState, key = fileId) && !isRemote

    // Acquire from the shared pool. Pooled instances arrive in IDLE state with
    // empty playlist (see VideoPlayerPool.release); the apply-block re-applies
    // the per-call attributes that the pool reset on the previous release.
    // Mute regime is fixed at acquire time — muted players are built without
    // an audio renderer entirely (no AudioTrack, no AudioMix wakelock).
    val player = remember {
        pool.acquire(muted = muted).apply {
            playWhenReady = autoPlay
            repeatModeOne = autoLoop
            this.muted = muted
        }
    }

    // Track player.playbackState to drive the mid-playback rebuffer overlay
    // separate from the pre-Ready download overlay. ExoPlayer (and AVPlayer)
    // flip into Buffering for many sub-second reasons that are NOT user-visible
    // "the video froze" events: source switch on quality change (~50-200ms),
    // seek (~100-300ms), normal chunk-boundary refills on tight buffers
    // (~50-150ms). Painting the indicator immediately on every such blip
    // produced a visible flash of the disc-and-spinner during healthy playback
    // — same UX bug the pre-Ready download path solved with
    // [rememberDeferredLoading]. Reusing the same primitive here so a true
    // network-rebuffer (>400ms of starved decoder) gets feedback while every
    // blip-and-recover stays invisible. 400ms < the 600ms used for the
    // download path because the user is mid-watch (more attentive); a longer
    // wait while the video is frozen reads worse than the same wait staring
    // at a thumbnail.
    val playbackState by player.playbackState.collectAsStateWithLifecycle()
    val showRebufferOverlay = rememberDeferredLoading(
        pending = playbackState == PlaybackState.Buffering,
        key = fileId,
        graceMs = REBUFFER_OVERLAY_GRACE_MS,
    )

    // React to autoPlay changes after acquisition — critical inside a HorizontalPager,
    // where neighbour pages stay composed past the active one (offscreenPageLimit ≥ 1).
    // Without this, a video that started playing on its active page keeps running
    // off-screen after the swipe (audio bleed-through, kept-alive MediaCodec, kept-alive
    // wakelock for non-muted players); a precomposed neighbour acquired with autoPlay=
    // false stays paused when it becomes the active page. Passing it through a
    // LaunchedEffect keyed on `autoPlay` flips playWhenReady on each transition.
    LaunchedEffect(autoPlay) { player.playWhenReady = autoPlay }

    // Swap the source when the file becomes Ready, or when the caller picks a
    // different quality (different fileId → new MediaState.Ready with a new path).
    // We preserve playback position across the swap so a quality flip resumes
    // mid-frame instead of restarting from zero.
    LaunchedEffect(mediaState, fileId, isRemote, remoteUrl) {
        val uri: String = if (isRemote) {
            // K2 smart-casts remoteUrl to String via the isRemote val above
            // (`fileId == 0 && remoteUrl != null`).
            remoteUrl
        } else {
            val ready = mediaState as? MediaState.Ready ?: return@LaunchedEffect
            if (ready.path.isEmpty()) return@LaunchedEffect
            "file://${ready.path}"
        }
        val resumeAt = player.currentPositionMs()
        val wasPlaying = player.playWhenReady
        player.setSource(uri)
        if (resumeAt > 0L) player.seekTo(resumeAt)
        player.playWhenReady = wasPlaying
    }

    DisposableEffect(player) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (autoPlay) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            // Hand the player back to the pool instead of releasing — saves the
            // MediaCodec/decoder allocation cost on the next viewport entry.
            pool.release(player, muted = muted)
        }
    }

    Box(modifier = modifier) {
        VideoPlayerView(
            player = player,
            modifier = Modifier.fillMaxSize(),
            aspectRatio = initialAspect,
            resizeMode = VideoResizeMode.Fit,
        )
        when (val s = mediaState) {
            is MediaState.Downloading -> if (showLoadingOverlay) Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaLoadingOverlay(
                    progress = s.progress,
                    downloadedBytes = s.downloadedBytes,
                    totalBytes = s.totalBytes,
                    onCancel = { binding.cancelExplicit() },
                )
            }
            is MediaState.Failed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaFailedOverlay(
                    onRetry = { coScope.launch { binding.retry(priority) } },
                )
            }
            MediaState.Idle -> if (showLoadingOverlay) Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaIndeterminateIndicator()
            }
            is MediaState.Ready -> Unit
        }
        // Mid-playback rebuffer overlay. Uses the same M3 Expressive
        // LoadingIndicator (polygon cycle) as the pre-download path, so a
        // stall during a watched video reads identically to a stall before
        // the file lands — single visual idiom for "busy". Only paints when
        // the file is actually local (mediaState is Ready) and the player
        // has been starved past [REBUFFER_OVERLAY_GRACE_MS]; the download
        // overlay above takes the pre-Ready stalls. Sub-grace blips
        // (seeks / source switches / brief chunk refills) never reach
        // showRebufferOverlay so the disc doesn't flash on healthy playback.
        if (mediaState is MediaState.Ready && showRebufferOverlay) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaIndeterminateIndicator()
            }
        }
        // Custom Compose chrome: 72dp polygon-morph play/pause, M3 Slider
        // seek bar, mute toggle, auto-hide, double-tap ±10s seek. Replaces
        // the stock media3 PlayerView controller surface. TDLib mode mounts
        // the chrome only after the file is local (pre-Ready the user sees
        // the download affordance via MediaLoadingOverlay above and playback
        // chrome would be in the way). Web mode (isRemote) skips the
        // MediaCache pathway entirely — the player streams from the URL and
        // owns its own ready-state — so the chrome mounts as soon as the
        // composable enters: any pre-roll buffering is handled via the
        // player's playbackState listener inside [VideoPlayerControls].
        if (showControls && (isRemote || mediaState is MediaState.Ready)) {
            VideoPlayerControls(
                player = player,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// 400ms grace window before the mid-playback rebuffer overlay paints. Catches
// real "video froze" events (>400ms of starved decoder, typical on cellular
// hand-off or tight buffer drain on big videos) while hiding sub-second blips
// (seek, source switch, chunk-boundary refill) that mean nothing to the user.
// Tighter than the 600ms used pre-Ready: the user is mid-watch and more
// attentive — a longer freeze with no feedback reads worse than the same
// wait while staring at a thumbnail.
private const val REBUFFER_OVERLAY_GRACE_MS = 400L
