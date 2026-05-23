# Phase H — Finish KMP migration

Continuation of Phase G. Target: every UI screen + repository compiles on iOS,
the iOS simulator app feels indistinguishable from Android (Material 3 Expressive
chrome, full PostCard, video, inline custom emoji, comments, auth UX), and
`androidMain` shrinks to ~40 files (TDLib JNI bridge + ExoPlayer + Lottie +
android-system shims + AppGraph wiring).

---

## Starting state (commit `dd5b1c6`, end of Phase G)

| Source set | Files |
|---|---|
| `androidMain/kotlin` | 111 |
| `commonMain/kotlin`  | 87  |
| `iosMain/kotlin`     | 9   |

Both targets compile clean (`./gradlew :androidApp:assembleDebug` and
`:shared:compileKotlinIosSimulatorArm64`). iOS simulator launches via the
existing `iosApp/iosApp.xcodeproj`; `IosAppGraph` drives a slim parallel
renderer (`MainViewController.kt`) off the shared `WebFeedSource`.

### What's already in `commonMain`

- **Data layer** — full `data/web/*` pipeline, `data/*` value types
  (`TimelinePost`, `PostContent`, `Country`, `ChannelInfo`, `UserProfile`,
  `MediaState`, `DownloadPriority`, `CustomEmojiSticker`, `ChatInvitePreview`,
  `LinkDialogState`, `StartupCoordinator`, `ReadCursors`, `FeedOrder`,
  `AppConfig`, `IgnoredChannelsStore`, `SubscriptionsStore`, `GuestModeStore`,
  `MigrationStore`), plus `StringResolver` (CMP `getString` via `runBlocking`).
- **KMP helpers** — `nowMs()`, `parseIsoToEpochMs()`, `PlatformLog`,
  `PlatformLocale`, `PlatformDispatchers.ioDispatcher`, `defaultWebHttpClient`
  (expect/actual; OkHttp on Android, Darwin on iOS),
  `preferencesDataStorePath` (Okio-backed KMP DataStore factory),
  `DriverFactory` (AndroidSqliteDriver / NativeSqliteDriver),
  `WebDatabaseProvider`.
- **Theme** — `Color`, `Type`, `Shape` (full `HortayExpressive` registry with
  `MaterialShapes` polygons + `MorphShape`/`PolygonShape` rewritten without
  `android.graphics.Path`), `Fonts` (expect/actual; Google Fonts on Android,
  `FontFamily.SansSerif` on iOS), `PressInteraction`.
- **Chrome** — `HortayTopBar` (`LargeFlexibleTopAppBar`), `FloatingNavBar`
  (`HorizontalFloatingToolbar`), `Brand`, `ConnectionBanner`, `FloatingTopBar`,
  `NavTab`, `Symbol`, all simple dialogs.
- **Timeline scaffolding** — `TimelineUiState`, `ChannelUiState`,
  `FeedScrollEffects`, `FeedSnapFling`, `SkeletonFeed`, `SmartScroll`,
  `TimelineFeedGrouping`, `TimelineEmptyHero`, `FoldersBar`, `UnreadCounterPill`,
  `PostInteractions`, `LocalReadCursors`.
- **Web UI** — `WebChannelScreen`, `WebChannelsScreen`, `WebSearchScreen`,
  `AddChannelSheet`, `MigrationProposalSheet`, `WebModeScaffold`, all of
  `ChannelsScreen` / `HiddenChannelsScreen`.
- **Pure media composables** — `LocalCenteredItem`, `LocalScrollGate`,
  `LocalInlineVideoAutoplay`, `LocalWebHttpClient`, `MediaResolver`,
  `SpoilerOverlay`, `SpoilerShimmer`, `TimeFormat`.
- **iOS shell** — `IosAppGraph`, `MainViewController` (rich parallel renderer:
  rich text, photo albums, reactions, channels tab, formatted dates via shared
  `TimeFormat.formatRelative`).

### The critical fix that unlocked everything in Phase G

`compose.material3` (Gradle DSL accessor, deprecated in CMP 1.12) resolves to a
stub variant that marks **every Expressive symbol** (`MaterialShapes`,
`MotionScheme`, `LargeFlexibleTopAppBar`, `HorizontalFloatingToolbar`,
`LoadingIndicator`, `MaterialExpressiveTheme`, `ButtonGroup`,
`ToggleFloatingActionButton`, the wavy progress indicators…) as `internal` in
the iOS klib publication. Switching to direct Maven coords —
`libs.jetbrains.compose.material3` (`org.jetbrains.compose.material3:material3`)
— pulls the real artifact and exposes the full Expressive surface to commonMain.
**Reference project:** `C:\GitHub\univera\univera-multiplatform-mobile`
(`shared/build.gradle.kts`) does the same thing.

Symbols **still stripped** from the iOS klib (only `$stableprop` scaffolding
present): none discovered. Everything Hortay uses is exposed.

---

## What's left in `androidMain` and what blocks each

Four orthogonal blockers explain almost every remaining file.

### Blocker 1 — Lottie inline-emoji animator (5 files)

The "single bitmap-bg rasterisation" optimisation in `CustomEmojiAnimator`
relies on `com.airbnb.lottie.LottieDrawable.draw(canvas)` — drawing N glyphs
into one shared bitmap via Choreographer, recoloured per-glyph by
`PorterDuffColorFilter`. Compottie has no `LottieDrawable` equivalent, so the
existing animator can't be moved verbatim.

Files: `CustomEmojiAnimator`, `InlineCustomEmojiRenderer`, `LottieUrlStore`,
`LottieStickerView`, `LocalCustomEmojiAnimator`.

**Transitive blast radius** (files that can't move until this is unblocked):
`CustomEmojiInlineView` → `FormattedTextRenderer` (`RenderableText`,
`rememberRenderableText`, `LinkRange`, `contentKey`, `spoilerGroups`) →
`RichText`, `LinkAwareText`, `LinkLongPress`, `LinkActionsSheet` → `PostBody` →
`PostCard` → `TimelineFeedColumn`, `ChannelScreen`, `CommentsScreen`,
`MainScaffold`, every screen that renders a post.

### Blocker 2 — ExoPlayer (5 files)

`ExoPlayerPool`, `TdVideoPlayer`, `WebmStickerPlayer`,
`LocalExoPlayerPool` directly use `androidx.media3.exoplayer.ExoPlayer`,
`Player.REPEAT_MODE_ONE`, `TextureView`, `setVideoTextureView` etc.

**Transitive blast radius:** `StickerView` (uses `LottieStickerView` *and*
`WebmStickerPlayer`), `TdMediaImage` (`rememberMediaBinding`),
`MediaProgressIndicator` (uses `effectiveSkeletonGrace` from the Android-only
`MediaBinding`), `MediaViewerHost`, `FullScreenMediaViewer`, `VideoNoteBubble`,
`VideoPlayerControls`.

### Blocker 3 — TDLib repos (25 files)

Everything under `data/` that imports `org.drinkless.tdlib.TdApi`:
`TdClient`, `PostsRepository`, `CommentsRepository`,
`ChannelActionsRepository`, `MediaCache`, `CustomEmojiRepository`,
`MessageMapper`, `MessageContentMapper`, `ChatFoldersRepository`,
`TelegramLinkResolver`, `LocaleStore`, `AutoDownloadStore`,
`MigrationCoordinator`, `WebCustomEmojiBridge`, `TdLifecycleBridge`,
`StatsRepository`, `report/ReportRepository`, etc., plus `AppGraph` (DI root).

**Transitive blast radius:** `AuthScreen` (`AppGraph.tdClient`, `Country`
lookups via `CountryRepository`), `ChannelInfoSheet` (`ChannelActionsRepository`),
`CommentsScreen` (`CommentsRepository`), `MainScaffold` (`AppGraph`),
`MainScaffoldDialogs` (`AppGraph`), `TabContentSwitcher` (`AppGraph`,
`TimelineScreen`, `SettingsScreen`), `ChannelScreen` (`PostsRepository`),
`TimelineScreen` (`PostsRepository`, `TimelineViewModel`),
`TimelineViewModel` / `ChannelViewModel`, `PostActions`
(`PostsRepository.canonicalShareUrl`), `DeepLinkDispatcher`
(`DeepLinkRouter` is already common but consumes
`PublicHandleResult.Channel`/`User`), `SettingsScreen` (every store +
several repos), `AutoDownloadScreen`, `UserProfileSheet`.

### Blocker 4 — Android system services + Context APIs

Clipboard / Intent / Toast / FileProvider / WindowCompat /
`androidx.activity.ComponentActivity` / `androidx.browser.customtabs` /
`android.app.Activity` (for the splash status-bar tweaks in
`Theme.android.kt`) / `coil3.video` / `coil3.gif`.

Files: `LinkActionsSheet`, `MediaShareActions`, `PostActions`,
`MainScaffoldDialogs`, `HortayUriHandler`, `LinkAwareScaffold`,
`FullScreenMediaViewer`, `MediaViewerHost`, `ChannelHeaderBar` (uses
`LocalUriHandler` — already KMP, no action), `MinithumbImage` (uses
`BitmapFactory.decodeByteArray` + `Bitmap.asImageBitmap`), `Theme.kt` (uses
`Activity` + `WindowCompat` for status bar).

---

## Execution plan (rough budgets)

Phase H is ~3-4 focused sessions. Order matters — every later step depends on
the abstractions defined in earlier ones.

### H1 — VideoPlayer expect/actual (~3-4 hours)

**Goal:** unblock Blocker 2. Every ExoPlayer call goes through an
`expect interface VideoPlayer` whose Android actual wraps the existing
`ExoPlayerPool`-managed `Player` and whose iOS actual wraps `AVPlayer`.

**Surface to define** (driven by what `TdVideoPlayer` + `WebmStickerPlayer`
actually call):

```kotlin
// commonMain/ui/media/VideoPlayer.kt
expect class VideoPlayer {
    val isPlaying: StateFlow<Boolean>
    val currentPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val playbackState: StateFlow<PlaybackState>  // Idle/Buffering/Ready/Ended

    fun setSource(uri: String)               // file:// or https://
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    var playWhenReady: Boolean
    var repeatModeOne: Boolean
    var muted: Boolean
    fun release()
}

expect class VideoPlayerPool {
    fun acquire(muted: Boolean): VideoPlayer
    fun release(player: VideoPlayer)
}

// commonMain/ui/media/VideoPlayerView.kt
@Composable
expect fun VideoPlayerView(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
)
```

**Android actual** — current `ExoPlayerPool` becomes `actual class
VideoPlayerPool`; `actual class VideoPlayer` is a thin wrapper around the
ExoPlayer `Player` interface (delegating `play()`/`pause()`/`setMediaItem(...)`
exactly as today; collecting `Player.Listener.onIsPlayingChanged` /
`onPlaybackStateChanged` into the StateFlows). `VideoPlayerView` is an
`AndroidView` over `TextureView` with `player.setVideoTextureView(view)` —
basically the existing `TdVideoPlayer` body trimmed.

**iOS actual** — `AVPlayer` + `AVPlayerLayer`. `VideoPlayerView` is a
`UIViewController`-backed Compose view via `ComposeUIViewController` /
`UIKitView`, hosting an `AVPlayerLayer` inside a `UIView`. Use
`addPeriodicTimeObserverForInterval` for `currentPositionMs`,
`AVPlayer.timeControlStatus` KVO for `isPlaying`. Pool stub can return a fresh
AVPlayer per acquire on iOS for v1 — Android's reuse is an optimisation for the
heavier ExoPlayer init cost, AVPlayer is cheap.

**Migration of consumers:** rewrite `TdVideoPlayer.kt` and `WebmStickerPlayer.kt`
to call the abstraction. They're large (~250 lines each) but mostly comments
and `Player.Listener` plumbing that maps 1:1 to flow collectors. Both files
then move to commonMain. `LocalExoPlayerPool` becomes `LocalVideoPlayerPool`,
`StaticCompositionLocalOf<VideoPlayerPool>`, in commonMain.

**Verification:** Android video posts still play with no behaviour change
(autoplay in feed, full-screen viewer, video stickers); iOS shows the first
frame for now (autoplay can come later; we just need the abstraction in place
so PostBody compiles).

### H2 — Compottie inline emoji (~4-6 hours)

**Goal:** unblock Blocker 1. Replace the `CustomEmojiAnimator` shared-bitmap
pipeline with a per-glyph Compottie renderer. **Accept** the perf regression —
hot benchmarks (1000 glyphs/sec animation budget) move from "shared canvas
rasterise" to "per-composable LottieAnimation". Compottie 2.2 + Skia rendering
is fast enough for the realistic load (≤ 30 inline emojis per visible card
viewport, capped by `LazyColumn` skippability), and the loss buys iOS parity.

**Files to rewrite:**

- `LottieStickerView` — already KMP-shaped via Compottie's
  `LottieAnimation(composition = ...)`. Drop the Airbnb Lottie path; use the
  Compottie wrapper that's already on the classpath
  (`io.github.alexzhirkevich:compottie`).
- `InlineCustomEmojiRenderer` — was a custom `Canvas` that the Animator stamped
  into. Becomes a `LottieAnimation` per glyph, sized to the inline text box
  via `Modifier.size(emojiSize)`. Use `rememberLottieComposition` with the TGS
  bytes resolved via `LottieUrlStore` (also a commonMain rewrite).
- `LottieUrlStore` — already mostly KMP (Ktor-based fetcher). Keep the
  in-flight dedup + LRU + negative cache (`atomicfu` for atomics; we did this
  pattern in `WebCustomEmojiResolver` in Phase G). Replace the Android-only
  `LottieComposition.fromJsonString` with Compottie's
  `LottieCompositionSpec.JsonString(...)`.
- `CustomEmojiAnimator` — DELETE. The shared-bitmap rasterisation
  optimisation goes away. Document the regression in `ARCHITECTURE.md` ("Phase
  H4: per-glyph Compottie renderer; if benchmarks regress in real-world
  channels, revisit with a SkiaImageDecoder-backed shared bitmap.").
- `LocalCustomEmojiAnimator` — DELETE.

**Migration of consumers:** `CustomEmojiInlineView` drops the
`CustomEmojiAnimator` path entirely, always routes TGS through the new inline
renderer. `FormattedTextRenderer.RenderableText` keeps its `InlineTextContent`
slot but the inline content is now a `LottieAnimation` not a `Canvas`.

**Verification:** Android channel feed renders inline custom emoji
identically (visual diff); cold-scroll a custom-emoji-heavy channel and confirm
frame times via `adb shell dumpsys gfxinfo dev.lyo.hortay framestats` stay
within the budget documented in `ARCHITECTURE.md`. If frame times regress >
20%, the Compottie path needs a `LaunchedEffect` that pauses
`progress.animateAsState` when `LocalIsHighlightedItem.current == false` *and*
the row is past the viewport — same idea the old animator implemented via the
shared Choreographer master clock.

### H3 — HortayBackend expect/actual + AppGraph split (~2 days)

**Goal:** unblock Blocker 3. The big one. Define a commonMain interface that
captures the surface UI screens actually use from TDLib repos; Android delegates
to the existing concrete repos, iOS provides stubs that return empty flows /
throw `UnsupportedOnIos` for write paths the user can't reach in guest mode.

**Strategy** (rejected the "translate all 200 TdApi types" approach as
multi-week — stays Option B from Phase G plan):

```kotlin
// commonMain/data/HortayBackend.kt
expect class HortayBackend {
    val authStage: StateFlow<AuthStage>
    val connection: StateFlow<ConnectionStatus>

    suspend fun resolvePublicChat(handle: String): Long?
    suspend fun previewChatInvite(link: String): ChatInvitePreview?
    suspend fun channelInfo(chatId: Long): ChannelInfo?
    suspend fun userProfile(userId: Long): UserProfile?

    suspend fun joinChat(chatId: Long): Boolean
    suspend fun leaveChat(chatId: Long): Boolean
    suspend fun muteChat(chatId: Long, muteFor: Int)

    suspend fun toggleReaction(post: TimelinePost, reaction: ReactionKind)
    suspend fun voteInPoll(post: TimelinePost, optionIds: IntArray)

    fun postsFeed(scope: FeedScope): StateFlow<PersistentList<TimelinePost>>
    fun chats(): StateFlow<PersistentList<ChannelEntry>>
    suspend fun refreshFeed()
    suspend fun loadOlder(chatId: Long)
    suspend fun loadHistoryAround(chatId: Long, messageId: Long)

    // … grow as UI screens move
}
```

The shape mirrors what `PostsRepository` / `ChannelActionsRepository` /
`CommentsRepository` already expose, **typed in commonMain value classes only**
(every parameter / return type already lives in commonMain — `TimelinePost`,
`ChannelInfo`, `UserProfile`, etc.). The existing repos stay androidMain; the
Android `actual class HortayBackend(appGraph: AppGraph)` constructor delegates
each method through.

**AppGraph split** (necessary because UI screens currently take `AppGraph`):

- Move pure-UI graph fields to `commonMain/SharedGraph.kt`:
  `subscriptions`, `guestMode`, `migrationStore`, `ignoredChannels`,
  `webDatabase`, `webRepository`, `webFeedSource`, `webHttpClient`,
  `userMessages`, `nav`, `deepLinkRouter`, `bookmarkStore`, `linkDialogs`,
  `reportDialogs`, `startupCoordinator`, `appScope`, `strings`,
  `connectivityBridge.connection`, `lifecycleBridge.foreground`.
- TDLib-bound fields (`tdClient`, `postsRepository`, `commentsRepository`,
  `channelActionsRepository`, `mediaCache`, `customEmoji`, `messageMapper`,
  `migrationCoordinator`, `webCustomEmojiBridge`) become a `tdBackend:
  HortayBackend` expect val on SharedGraph; the Android actual exposes the
  concrete repos by composition, the iOS actual returns a stub.
- Every UI file that currently takes `graph: AppGraph` migrates to
  `graph: SharedGraph` + `tdBackend: HortayBackend` (or `tdBackend:
  HortayBackend?` for screens that should hide entirely in guest mode).
  ViewModels (`TimelineViewModel`, `ChannelViewModel`, etc.) take
  `HortayBackend` in their constructor instead of `PostsRepository`.

**Iteration order** (move one screen at a time, verify each):

1. `AuthScreen` + `CountryPickerSheet` — `Country` is already common; AuthScreen
   needs `tdBackend.authStage`, `submitPhone`, `submitCode`, `submitPassword`,
   `cancelAuth`, `signOut`. Define those on `HortayBackend`, move.
2. `ChannelInfoSheet` — `tdBackend.channelInfo(chatId)`, `joinChat`,
   `leaveChat`, `muteChat`. Move.
3. `UserProfileSheet` — `tdBackend.userProfile(userId)`. Move.
4. `CommentsScreen` — biggest single screen. `tdBackend.comments(chatId,
   parentMessageId)`, `toggleReaction`, `loadOlder`. Move. Drags
   `CommentsRepository` surface area but **the repository class stays
   androidMain** — only its public method shapes graduate to `HortayBackend`.
5. `MainScaffold` + dialogs + `NavOverlayRenderer` + `TabContentSwitcher` —
   only need `SharedGraph` + `tdBackend`. Move.
6. `TimelineScreen` + `TimelineViewModel` + `ChannelScreen` + `ChannelViewModel`
   — feed-bound. Heavy. Move.
7. `SettingsScreen` + `AutoDownloadScreen` + `HiddenChannelsScreen` — need
   `tdBackend.autoDownload`, `tdBackend.hiddenChannels`, `tdBackend.localeStore`,
   `tdBackend.statsRepository`. Move.

**Verification per step:** Android build clean + screen-by-screen smoke test
on emulator (every screen the iOS guest-mode parallel renderer already covers
keeps working in TDLib mode on Android).

### H4 — Android system services expect/actual (~1 day)

**Goal:** unblock Blocker 4. Small, mechanical.

Shape the abstractions:

```kotlin
// commonMain/Platform/PlatformClipboard.kt
expect object PlatformClipboard {
    suspend fun writeText(label: String, text: String)
    suspend fun writeImage(label: String, mime: String, bytes: ByteArray)
}

// commonMain/Platform/PlatformShare.kt
expect object PlatformShare {
    suspend fun shareText(text: String, mime: String = "text/plain")
    suspend fun shareFile(path: String, mime: String)
    suspend fun shareUrl(url: String)
}

// commonMain/Platform/PlatformToast.kt
@Composable
expect fun rememberToaster(): Toaster
interface Toaster { fun show(text: String, durationMs: Long = 2000L) }

// commonMain/Platform/PlatformBitmap.kt
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

// commonMain/ui/theme/Theme.kt (already commonMain — replaces android Activity)
expect class StatusBarController {
    fun setLightAppearance(light: Boolean)
}
```

Android actuals route through `Context.getSystemService(ClipboardManager)`,
`ACTION_SEND` Intents, `Toast.makeText`, `BitmapFactory.decodeByteArray` +
`Bitmap.asImageBitmap`, `WindowCompat.getInsetsController`. iOS actuals route
through `UIPasteboard.generalPasteboard`, `UIActivityViewController` +
`UIDocumentInteractionController` for files, a SwiftUI overlay `Text(...)` for
toasts (or use `MainViewController` to post the message), `UIImage` →
`ImageBitmap` (Skia decode from bytes — try `org.jetbrains.skia.Image.makeFromEncoded`
which is already on the classpath via CMP).

`PlatformContextHolder` already exists for the DataStore factory — iOS
actuals that need a UIViewController root can use a similar holder seeded from
`MainViewController` (`UIApplication.sharedApplication.keyWindow?.rootViewController`
works but the seed-on-mount pattern is cleaner).

**Files unblocked:** `LinkActionsSheet`, `MediaShareActions`, `PostActions`,
`MinithumbImage`, `Theme`, `HortayUriHandler` (open URL — KMP via
`LocalUriHandler`, already works), `LinkAwareScaffold`.

### H5 — iOS UI parity ship (~1 day)

**Goal:** the iOS app uses the **real** PostCard / TimelineScreen / MainScaffold
tree from commonMain. Replace `MainViewController`'s parallel renderer with
`HortayApp` wired to `IosAppGraph` (which now extends `SharedGraph` and
provides a stub `HortayBackend.iosGuestStub` that throws on TDLib-only paths).
The iOS guest-mode UX becomes pixel-for-pixel the Android guest-mode UX (same
WebModeScaffold from commonMain).

Steps:

1. Update `IosAppGraph` to construct a `SharedGraph` + `HortayBackend` stub.
2. Replace `MainViewController.kt`'s 480-line parallel renderer with:
   ```kotlin
   fun MainViewController(): UIViewController = ComposeUIViewController {
       HortayTheme {
           WebModeScaffold(graph = sharedGraph, backend = stubBackend)
       }
   }
   ```
3. Run on simulator, smoke-test the feed / channels / search / add channel /
   migrate proposal / external link / settings (the subset that doesn't need
   TDLib). Anything that crashes traces back to a `HortayBackend` method whose
   stub still throws — replace with empty flow / `null` return.

### H6 — TDLib iOS port? (defer)

Out of scope for Phase H. Phase I (separate plan) would cross-compile
`libtdjni` for iOS via the existing Docker build, write cinterop `.def` files
for ~200 `TdApi.*` types, port `TdClient.kt` to use the cinterop bindings, and
flip `HortayBackend.ios.actual` to the real impl. Multi-week. Guest-mode iOS
parity from Phase H is enough to ship.

---

## Critical decisions discovered in Phase G — DO NOT relitigate

1. **Direct CMP Maven coords, not the `compose.*` DSL.** This is the entire
   reason Material3 Expressive works on iOS. If you find yourself adding
   `compose.material3` or `compose.foundation` etc., back-button immediately.

2. **`compose-multiplatform = "1.12.0-alpha01"`.** Tried `1.12.0-alpha02+dev4168`
   from the JetBrains dev maven — same Expressive accessibility, no extra
   benefit, dev builds churn. Stay on alpha01 stable.

3. **`-Xexpect-actual-classes` + `-Xskip-prerelease-check`** in
   `shared/build.gradle.kts` are load-bearing for clean output. Don't remove.

4. **`kotlinx-datetime = "0.6.2"`** in `libs.versions.toml` but gets resolved to
   `0.7.1` transitively (via CMP material3). Use `kotlin.time.Clock` /
   `kotlin.time.Instant` (Kotlin 2.x stdlib), not `kotlinx.datetime.Clock` —
   the latter was removed from `kotlinx.datetime` in 0.7.

5. **`atomicfu` for atomics, `kotlinx.atomicfu.locks.synchronized` for
   `Mutex`-light locks** (cheaper than `Mutex.withLock` for hot paths). We
   already use this pattern in `WebFeedSource` (inFlightRetries) and
   `WebCustomEmojiResolver` (gateUntilMs); replicate when porting
   `MediaCache.activePriority`, `CustomEmojiAnimator.cache`, etc.

6. **`androidx.graphics.shapes 1.1.0` is KMP** (with iOS arm64 / simulator-arm64
   / x64 variants) but its `toPath()` extension is Android-only — it returns
   `android.graphics.Path`. Use `RoundedPolygon.cubics` / `Morph.asCubics()` to
   walk cubics manually and build a Compose `Path` via `cubicTo` — example in
   `commonMain/ui/theme/Shape.kt::MorphShape::createOutline`.

7. **`String.format("%.1f", …)` is JVM-only.** Use a manual `(value * 10).toLong() / 10.0`
   + tenth digit split — example in
   `commonMain/ui/timeline/TimelineFeedGrouping.kt::formatSubscribers::compact`.

8. **`androidx.compose.runtime.Immutable` works in commonMain via CMP**, even
   on iOS — the annotation is part of `org.jetbrains.compose.runtime:runtime`.
   Don't substitute with anything custom.

9. **The portability check script lives at `scripts/check-portable.py`.** Walks
   `import dev.lyo.hortay.*` lines per file and resolves each symbol against
   `commonMain` / `androidMain`. Use it before every batch move to catch
   transitive deps. Same-package implicit references (no import line) are NOT
   caught — when moving files in `ui/media/`, also grep for `ExoPlayerPool`,
   `MediaCache`, `CustomEmojiRepository` references in the file body.

10. **Mac mini SSH** at `192.168.88.142` (`dvaderfun` / `1SideDark1`) for iOS
    framework link + simulator runs. **Set up an SSH key the first time** so
    builds can run unattended — Windows OpenSSH has no `sshpass` / `plink` and
    password prompts will block automation. After `ssh-copy-id`, the rebuild
    cycle is:
    ```bash
    ssh dvaderfun@192.168.88.142 'cd ~/hortay-android && git pull && rm -rf shared/build && \
      xcrun simctl uninstall booted dev.lyo.hortay 2>/dev/null; \
      xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
        -destination "platform=iOS Simulator,name=iPhone 15" build'
    ```

11. **Don't bulk-move 50+ files at once.** Phase G discovered (twice) that
    blast-radius is hard to predict and the revert is messy. Move in waves of
    5-15 with `compileAndroidMain :shared:compileKotlinIosSimulatorArm64`
    between each.

12. **When a moved file breaks the build, revert before fixing.** It's faster
    to `git checkout` the file back to `androidMain`, fix its dependency
    (extract the data class, define the expect/actual, etc.), and re-move,
    than to dig through cascading errors.

---

## Verification gates

End of each step (H1 / H2 / H3 each-screen / H4 / H5):

```bash
./gradlew :androidApp:assembleDebug             # Android still builds
./gradlew :shared:compileKotlinIosSimulatorArm64 # iOS Kotlin compiles
./gradlew :androidApp:installDebug              # smoke-test on emulator
# on Mac mini:
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild ... build && simctl install booted && simctl launch booted dev.lyo.hortay
```

End of Phase H (full):

- `find shared/src/androidMain/kotlin -name "*.kt" | wc -l` ≤ 40
- `find shared/src/commonMain/kotlin -name "*.kt" | wc -l` ≥ 150
- `find shared/src/iosMain/kotlin -name "*.kt" | wc -l` ≥ 20 (VideoPlayer,
  Compottie fallback if any, Platform* iOS actuals, AppGraph stubs, MainViewController)
- iOS simulator launches Hortay; **add a real channel handle like `durov` and
  verify**: posts load with full PostCard chrome (avatar, header, formatted
  text, photo album grid, inline custom emoji animated via Compottie,
  reactions, view count, forward chip), reactions are tappable (no-op on iOS
  in guest mode is OK as long as the tap doesn't crash), channels tab lists
  every subscribed handle with avatar + subscriber count, settings opens
  without crashing.
- Android `:androidApp:lintRelease` + `:shared:detekt` both pass.

---

## Open questions to defer to Phase I

- **TDLib iOS port** (Phase I — cinterop). Multi-week. Lets iOS run
  authenticated mode. Not blocking guest-mode parity.
- **Wear OS target.** Univera's `gradle/libs.versions.toml` includes Wear
  Compose; Hortay doesn't. Decide before committing the codebase to a
  module-split that would help / hurt Wear.
- **WebAssembly / Desktop targets.** Almost free after Phase H (everything
  in `commonMain` already targets `iosMain` + `androidMain`; Wasm needs only a
  `wasmJsMain` source set + `Js`-engine Ktor client). Useful for a web demo.
- **Migrate Coil custom-emoji thumbs to Compottie's poster path** instead of
  the static WEBP fallback — would give animated WebM custom emoji on iOS via
  Compottie's WebP decoder. Verify Compottie supports animated WebP first.

---

## Reference projects

- **Univera multiplatform mobile** at `C:\GitHub\univera\univera-multiplatform-mobile`
  — the project that taught us about direct CMP Maven coords (see Phase G
  fix). Same CMP version, same Kotlin (well, 2.3.21 — Hortay is on 2.3.10, no
  blocker). Also a good template for: Koin DI (Hortay uses manual AppGraph —
  fine to keep; but Univera's koin setup is a reasonable Phase I option once
  the graph grows), Room (Hortay uses SQLDelight — no migration planned),
  Glance (not relevant to Hortay), navigation3 (Hortay uses overlay-driven
  NavStack — keep, see `ARCHITECTURE.md` "Compose Navigation typed routes"
  hard rule).

- **Compose Multiplatform 1.12.0-alpha01 release notes** —
  <https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0-alpha01>.

- **Material3 KLIB API surface** —
  `gh api repos/JetBrains/compose-multiplatform-core/contents/compose/material3/material3/api/material3.klib.api?ref=jb-main`
  reveals what's actually exposed on iOS. Re-check this before assuming
  anything is stripped.
