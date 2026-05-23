package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic playback state. Maps cleanly to ExoPlayer's `STATE_*` ints
 * (Android) and to AVPlayer's `AVPlayerItem.status` + `timeControlStatus` combo
 * (iOS).
 */
enum class PlaybackState { Idle, Buffering, Ready, Ended }

/**
 * How a video's content is fit inside its layout box.
 *
 * * [Fit] — letterbox to source aspect (Android `RESIZE_MODE_FIT`, iOS
 *   `resizeAspect`). Default — feeds, fullscreen viewer.
 * * [Zoom] — crop to fill, preserving aspect (Android `RESIZE_MODE_ZOOM`, iOS
 *   `resizeAspectFill`). Round video notes use this so the circle stays full
 *   when the source isn't perfectly square.
 */
enum class VideoResizeMode { Fit, Zoom }

/**
 * Thin platform-agnostic video player. Wraps ExoPlayer on Android and AVPlayer
 * on iOS. Acquired from [VideoPlayerPool] (so feeds don't churn decoder
 * allocations on fast scroll), bound to a view via [VideoPlayerView].
 *
 * State is exposed as flows so Compose call sites collect once via
 * `collectAsState` instead of every renderer attaching its own platform-native
 * listener. `currentPositionMs()` stays a synchronous getter — ExoPlayer /
 * AVPlayer don't push per-millisecond updates, so callers do their own throttled
 * polling (typically 100ms while playing, 500ms paused).
 */
expect class VideoPlayer {
    /** True iff playback is currently advancing the timeline. */
    val isPlaying: StateFlow<Boolean>

    /** Coarse pipeline state — see [PlaybackState] for mapping. */
    val playbackState: StateFlow<PlaybackState>

    /** Total media length in ms, or 0 before [PlaybackState.Ready] lands. */
    val durationMs: StateFlow<Long>

    /**
     * Source video aspect (width / height). 0f until the platform reports the
     * decoded format. Callers seed letterboxing from poster geometry up-front
     * so the first layout pass is correct; this flow updates if the decoder
     * disagrees with the seed (cheap one-shot re-layout).
     */
    val videoAspect: StateFlow<Float>

    /**
     * Latches true the moment the first decoded frame lands on the bound view.
     * Stickers gate their poster underlay on this signal — hiding earlier (e.g.
     * on bytes-on-disk) leaves a transparent-TextureView window where the
     * surface behind reads through.
     */
    val firstFrameRendered: StateFlow<Boolean>

    /**
     * Mirrors the platform's volume state. Reads as a flow so the controls
     * surface's mute toggle stays truthful when the system audio route or
     * volume slider changes the level out from under the app.
     */
    val isMuted: StateFlow<Boolean>

    /**
     * Whether the player should auto-advance when prepared / un-paused. Setting
     * to true while ended is a no-op on Android (caller must `seekTo(0)` first);
     * [VideoPlayerControls] handles that branch.
     */
    var playWhenReady: Boolean

    /** Loop the current media indefinitely when it reaches the end. */
    var repeatModeOne: Boolean

    /**
     * Silences output. Note: for **muted-acquired** players (pool sub-pool A),
     * the underlying ExoPlayer is built without an audio renderer entirely —
     * setting [muted] = false is a no-op. Toggling audio on/off mid-clip
     * requires re-acquiring through the other sub-pool (see [VideoPlayerPool]).
     */
    var muted: Boolean

    /**
     * Set the playback source. Accepts `file://` and `https://` URIs. Calling
     * with the current URI is a no-op; calling with a new one preserves the
     * previous playback position so quality-switch paths can resume mid-frame
     * by re-`seekTo`ing after.
     */
    fun setSource(uri: String)

    /** Start / resume playback. No-op if already at end (see [playbackState]). */
    fun play()

    /** Pause playback. Does not release the decoder. */
    fun pause()

    /** Jump to absolute position. Clamped to `[0, durationMs]`. */
    fun seekTo(positionMs: Long)

    /**
     * Polled position read in ms. Cheap on both platforms (no main-thread
     * round-trip); intended for `produceState` loops at 100ms/500ms cadence.
     */
    fun currentPositionMs(): Long
}

/**
 * Two sub-pool reservoir of [VideoPlayer] instances.
 *
 * Why two sub-pools: a **muted** player is built without an audio renderer at
 * all (no `AudioTrack`, no `AudioMix` system wakelock), and the vast majority
 * of usage is muted (timeline autoplay, WebM stickers, custom emoji). Mixing
 * audio-capable instances into the muted pool re-arms the audio path for users
 * who don't need it. The pool keeps each regime warm independently.
 *
 * Pool reuse only happens between callers of the same mute regime; toggling
 * audio mid-clip is rare enough that releasing back and re-acquiring through
 * the other sub-pool is acceptable. [dev.lyo.hortay.ui.timeline.VideoNotePlayerBubble]
 * uses `key(muted) { ... }` to drive exactly that swap when the user taps the
 * mute chip.
 *
 * Threading: platform players touched only from the UI thread. All Compose call
 * sites already are, so no extra synchronisation is needed beyond the
 * `@Synchronized` belt on `acquire`/`release` for defensive correctness.
 */
expect class VideoPlayerPool {
    /** Hand out an instance of the requested mute regime. */
    fun acquire(muted: Boolean): VideoPlayer

    /**
     * Return [player] to its sub-pool. The pool resets state (clears playlist,
     * resets volume, parks in IDLE) before pooling so the next caller gets a
     * known starting state. Releases if the sub-pool is already at capacity.
     */
    fun release(player: VideoPlayer, muted: Boolean)

    /** Tear down every pooled instance. Call on process / Activity destroy. */
    fun shutdown()
}

/**
 * Process-singleton pool for video playback. Provided by `AppGraph` /
 * `IosAppGraph` at the top of the composition.
 */
val LocalVideoPlayerPool = staticCompositionLocalOf<VideoPlayerPool> {
    error(
        "VideoPlayerPool was not provided. " +
            "Wrap your composition in CompositionLocalProvider(LocalVideoPlayerPool provides …).",
    )
}

/**
 * Renders [player]'s video output. Wraps an Android `TextureView` inside an
 * `AspectRatioFrameLayout` on Android; wraps an `AVPlayerLayer` inside a
 * `UIView` on iOS via `UIKitView`.
 *
 * The view binds the player to its texture/layer on enter and releases on
 * exit; the player instance itself is owned by the caller (typically
 * `remember { pool.acquire(muted) }` with a `DisposableEffect.onDispose {
 * pool.release(...) }`).
 *
 * The view always renders with transparent background (Android: `isOpaque =
 * false`; iOS: `backgroundColor = clear`). WebM stickers carry a real alpha
 * channel and the rest of the codebase has no opaque-video call site.
 *
 * @param aspectRatio seed letterboxing — pass the poster's width/height so the
 *   first layout pass is correct. `0f` means "wait for decoder". The view
 *   updates internally when [VideoPlayer.videoAspect] reports the real ratio.
 */
@Composable
expect fun VideoPlayerView(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 0f,
    resizeMode: VideoResizeMode = VideoResizeMode.Fit,
)
