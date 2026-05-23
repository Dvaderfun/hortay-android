# Phase I — Finish the KMP migration and ship the iOS app

Continuation of Phase H. Target: every UI screen + repository compiles on iOS, the iOS simulator app feels indistinguishable from Android (same `MainScaffold` / `WebModeScaffold` / `PostCard` tree, Material 3 Expressive chrome, full media stack), and `androidMain` shrinks to roughly **30 files** — the irreducible set of Activity / Application / `AppGraph` wiring plus TDLib-bound repositories.

---

## Progress (session of 2026-05-23 → 2026-05-24)

**Counts:** 57 androidMain / 158 commonMain / 17 iosMain
(started session at 61 / 150 / 17, started Phase H at 111 / 87 / 9).

**Wave order revised bottom-up** because dependencies run leaf → root:
`MainScaffoldHelpers → TimelineScreen → ReportFlowSheet`. Promote types
first, move leaves, then containers.

### Committed
- **I-A** (`e30a894`) — platform surfaces + Settings/ReportLog/Theme to commonMain.
- **Wave A partial** (`2ec888e`) — AutoDownload types + Stats types to commonMain.
- **Wave A finish** (`7591d76`) — `ReportOption` / `ReportState` / `ReportStep` /
  `ReportFlowController` to commonMain; `ReportRepository` (Android) maps
  `TdApi.ReportOption` → DTO. `TranslationsStore.Key` promoted to `TranslationKey`
  + `TranslationsFacade` interface. `FolderTab` DTO + `FoldersFacade` interface
  added in commonMain (no wiring yet — `ChatFoldersRepository` still vends
  TdApi types; conversion lands when TimelineScreen moves).
- **Wave B partial** (`6e2af6f`) — `MediaShareActions` interface +
  `LocalMediaShareActions` in commonMain; `AndroidMediaShareActions` impl on
  Android side. `FullScreenMediaViewer` + `MediaViewerHost` move to commonMain.
- **Wave B finish** (`63cc015`) — `ReportFlowSheet` to commonMain;
  `ReportFlowViewModel` collapsed into `rememberCoroutineScope` +
  `remember(openToken)` (no `lifecycle-viewmodel-compose` dep needed in
  commonMain). `LocalGuestReportDelegate` typealias slot added; the legacy
  `GuestReportDelegator` class stays in androidMain until WebModeScaffold
  moves.
- **Wave C partial** (`ed16414`) — exposes the easier half of the backend
  surface: `isAuthenticated`, `logOut`, `reportController`, `reportDialogs`,
  `reportLogStore`, `reportExplainerStore`, `settingsStore`, `translations`.
  `IosAppGraph` now owns its own `SettingsStore` / `ReportLogStore` /
  `ReportExplainerStore` so the iOS stub backend has real instances to vend.

### Next session resume points
1. **Finish Wave C**: add the feed-orchestration half of `HortayBackend` —
   `feedPosts` already exists; add `newArrivals`, `refreshFeed`, `loadOlder`,
   `loadHistoryAround`, `openChat`, `closeChat`, `loadChannelHistory`,
   `hasWarmChannelHistory`, `chatTitle`, `channelSubscribersFlow`, `chatAvatar`,
   `searchInChannel`, the optimistic-reaction quartet
   (`applyOptimisticReaction` / `applyOptimisticPollAnswer` / `clearPollPending`
   / `toggleReaction` / `setPollAnswer`), and `resolvePublicHandle` /
   `resolveChatKind`. Stats + AutoDownloadStore exposures too (the facades
   landed already in `2ec888e`).
2. **Wave D wave 1**: move `MainScaffoldDialogs`, `NavOverlayRenderer`,
   `TabContentSwitcher` (already mostly KMP — refactor each to take
   `backend: HortayBackend` + the stores they read, drop direct AppGraph
   reference).
3. **Wave D wave 2 (the big one)**: `MainScaffold` (~557 lines).
4. **Wave D wave 3**: `TimelineScreen`, `ChannelScreen`, `ChannelViewModel` —
   biggest UI bite; split across two commits if needed.
5. **Wave D wave 4-6**: WebModeScaffold + AddChannelSheet +
   MigrationProposalSheet; SettingsScreen + AutoDownloadScreen;
   ReportFlowSheet remnants (`MainScaffoldDialogs` still wires it from
   `graph` — needs to switch to `backend.reportController`).
6. **Wave D wave 7**: `Theme.kt` — last UI file.
7. **I-D + I-E** per the plan below.

---

---

## Where we are (head of `main`, end of Phase H)

| Source set | Files |
|---|---|
| `androidMain/kotlin` | **57** |
| `commonMain/kotlin`  | **158** |
| `iosMain/kotlin`     | **17** |

Down from 111 / 87 / 9 at the start of Phase H. Both `:androidApp:assembleDebug` and `:shared:compileKotlinIosSimulatorArm64` are green at every commit on the branch.

### What's in commonMain already

- **Backend** — `HortayBackend` expect/actual + a stub iOS. Surface: auth + countries; channels / users; links; feed posts + thread observe / view; canonical share URL.
- **Platform helpers** — `Platform.kt` (clipboard, share, toaster, animator-duration-scale); `MediaCache` expect/actual; `CustomEmojiRepository` expect/actual; `VideoPlayer` / `VideoPlayerPool` / `VideoPlayerView` expect/actual; `LocalAvatarFileLoader` slot; `LocalStickerOutline` lambda slot; `LocalPlatformToaster`; `LocalMediaViewer` + `MediaViewerController`.
- **Data layer** — every store that lives on a `DataStore<Preferences>`: `BookmarkStore`, `ReportExplainerStore`, `IgnoredChannelsStore`, `GuestModeStore`, `MigrationStore`, `SubscriptionsStore`. Plus `NavStack` (atomicfu counter), `TapNavigation`, `ThreadState`, `PublicHandleResult`.
- **Theme + chrome** — full `HortayExpressive` registry, top bars, floating nav, brand, connection banner, dialogs.
- **Text** — `RichText`, `LinkAwareText`, `FormattedTextRenderer`, `LinkLongPress`, `LinkActionsSheet`, `HortayUriHandler`, `LinkAwareScaffold`.
- **Media** — `TdMediaImage`, `MinithumbImage`, `TdAvatar`, `MediaBinding`, `MediaProgressIndicator`, `LottieStickerView`, `WebmStickerPlayer`, `StickerView`, `TdVideoPlayer`, `VideoPlayerControls`, `VideoNoteBubble`, `VideoQualityPicker`, `CustomEmojiInlineView`.
- **Post tree** — `PostBody` (1.5k lines), `PostCard`, `PollBlock`, `ChannelHeaderBar`, `NewPostsPill`, `ChannelBadge`, `TimelineFeedColumn`, `TimelineViewModel`.
- **Screens** — `AuthScreen`, `ChannelInfoSheet`, `UserProfileSheet`, `HiddenChannelsScreen`, `CommentsScreen`, `ChannelsScreen`, plus all of `WebChannelScreen`, `WebChannelsScreen`, `WebSearchScreen`.

### What's still in androidMain (61 files)

| Category | Files | Notes |
|---|---|---|
| Entry / DI | `AppGraph.kt` | Necessarily Android — wires every TDLib repo. |
| Platform expect-actuals | `Platform.android.kt`, `PlatformDispatchers.android.kt`, `PlatformLocale.android.kt`, `PlatformLog.android.kt`, `PreferencesDataStoreFactory.android.kt`, `WebHttpClient.android.kt`, `DriverFactory.android.kt`, `WebDatabaseProvider.android.kt`, `HortayBackend.android.kt`, `MediaCache.android.kt` (currently named `MediaCache.kt`), `VideoPlayer.android.kt`, `Fonts.android.kt` | Stay — these are the actual sides of the expect declarations. |
| TDLib data layer | `TdClient`, `TdSender`, `TdErrorMapping`, `TdLifecycleBridge`, `AuthErrorMessages`, `ChannelActionsRepository`, `ChatFoldersRepository`, `ChatPresence`, `CommentsRepository`, `CountryRepository`, `CustomEmojiRepository.android.kt` (currently `CustomEmojiRepository.kt`), `LocaleStore`, `MediaAutoDownloader`, `MessageContentMapper`, `MessageMapper`, `PostsRepository`, `ChannelMetadataSync`, `SnapshotRestorer`, `ReportRepository`, `MigrationCoordinator`, `WebCustomEmojiBridge`, `TelegramLinkResolver`, `TimelineSnapshotStore`, `TranslationsStore`, `StatsRepository`, `CoroutineUtil`, `StickerOutlineStore` | Stay — every file imports `org.drinkless.tdlib.TdApi`. Phase II (TDLib iOS port via cinterop) would unlock these. |
| Storage stores that still take Android `Context` | `AutoDownloadStore`, `SettingsStore`, `ReportLogStore` | **Movable** with KMP `DataStore` + okio `FileSystem`. See I-A below. |
| UI screens — heavy, still depend on AppGraph or untyped repo handles | `MainScaffold`, `MainScaffoldDialogs`, `NavOverlayRenderer`, `TabContentSwitcher`, `TimelineScreen`, `ChannelScreen`, `ChannelViewModel`, `SettingsScreen`, `AutoDownloadScreen`, `WebModeScaffold`, `AddChannelSheet`, `MigrationProposalSheet`, `ReportFlowSheet`, `ReportFlowViewModel`, `GuestReportDelegator` | **Movable** in stages. Each needs a slice of `HortayBackend` to grow, plus the platform abstractions in I-B below. |
| Android-only system surfaces | `Theme.kt` (status-bar via `Activity`), `MediaShareActions`, `FullScreenMediaViewer`, `MediaViewerHost` | **Movable** once `StatusBarController` / `PlatformImageSaver` expect-actuals land. See I-B. |

---

## North star

> A user with the Hortay app on an iPhone in guest mode should see the **same** `WebModeScaffold` tree, the **same** `PostCard`, the **same** sheets, animations and motion-scheme, as a user on Android in guest mode. Phase II adds the TDLib bridge for authenticated mode; Phase I makes guest mode pixel-identical and shrinks androidMain to the irreducible 30-file core.

Concretely, end-of-phase counts target:

| Source set | Target |
|---|---|
| `androidMain/kotlin` | **30–32** |
| `commonMain/kotlin`  | **170+** |
| `iosMain/kotlin`     | **20+** |

---

## Execution plan

The phases below are designed to be committed one at a time. Each step ends with `./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug` green. Move in waves of 5–15 files between verification points — Phase H learned this the hard way (twice).

### I-A — Platform surfaces (~half day)

Mechanical expect/actual work. Unlocks every remaining store + several UI files.

**`expect fun applicationFilesPath(name: String): Path`** — commonMain helper that returns an okio `Path` under the app's private writable directory. Android: `PlatformContextHolder.require().filesDir.resolve(name).absolutePath.toPath()`. iOS: NSDocumentDirectory + name (same pattern as `preferencesDataStorePath`).

**`expect class StatusBarController`** — paints the system status bar light / dark from a Compose theme. Android: wraps `WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars = light`. iOS: sets `UIStatusBarStyle` via the hosting `UIViewController` (preferredStatusBarStyle override or set on root). Provided through `LocalStatusBarController` from `MainActivity` / `MainViewController`.

**`expect class PlatformImageSaver`** — saves bytes to the user's photo library.
- `suspend fun saveImage(bytes: ByteArray, mime: String, displayName: String): Result`
- `suspend fun saveVideo(path: String, mime: String, displayName: String): Result`
- `suspend fun copyImageToClipboard(bytes: ByteArray, mime: String): Result`
Android: existing `MediaShareActions` logic moves behind this class. iOS: `PHPhotoLibrary.performChanges` for save; `UIPasteboard.image = ...` for clipboard.

**`expect class PlatformConnectivity`** — small reactive surface for "what network are we on right now?".
- `val networkType: StateFlow<HortayNetworkType>` (Wifi / Cellular / Roaming / Offline)
Android: wraps `ConnectivityManager` callbacks. iOS: `nw_path_monitor` via cinterop.

**`expect class PlatformLanguagePicker`** — sets the in-app locale.
- `fun setAppLanguage(tag: String?)` (null = system default)
- `suspend fun currentTag(): String?`
Android: `AppCompatDelegate.setApplicationLocales` (existing `LocaleStore`). iOS: `UserDefaults.standard.set([tag], forKey: "AppleLanguages")` + restart prompt.

After landing I-A:
- `ReportLogStore` rewrites in commonMain using okio `FileSystem` + `applicationFilesPath`.
- `AutoDownloadStore` rewrites — the DataStore half goes commonMain via the existing KMP factory; the `TdClient.setAutoDownloadSettings` write side stays as a method on `HortayBackend`.
- `SettingsStore` migrates the same way: commonMain DataStore + a backend method for the TDLib-side persistence call (if any).
- `Theme.kt` swaps the status-bar `Activity` cast for `LocalStatusBarController.current.setLightAppearance(...)`. Moves to commonMain.

### I-B — Expand HortayBackend (~half day, parallelisable with I-A)

Add the methods every remaining screen calls. Group by consumer for review:

```kotlin
expect class HortayBackend {
    // ── existing surface stays ────────────────────────────────────────

    // ── Feed orchestration (TimelineScreen + ChannelScreen) ──────────
    val newArrivals: SharedFlow<TimelinePost>       // PostsRepository.newArrivals
    suspend fun refreshFeed()                       // PostsRepository.refresh
    suspend fun loadOlder()                         // PostsRepository.loadOlder
    suspend fun loadHistoryAround(chatId: Long, messageId: Long)
    suspend fun openChat(chatId: Long)              // ChatPresence.open via PostsRepository
    suspend fun closeChat(chatId: Long)
    suspend fun loadChannelHistory(chatId: Long)
    fun hasWarmChannelHistory(chatId: Long): Boolean

    suspend fun chatTitle(chatId: Long): String?
    fun channelSubscribersFlow(chatId: Long): StateFlow<Int?>
    suspend fun chatAvatar(chatId: Long): TdMedia?

    fun searchInChannel(chatId: Long, query: String): Flow<PersistentList<TimelinePost>>

    fun applyOptimisticReaction(chatId: Long, messageId: Long, kind: ReactionKind, nowChosen: Boolean)
    fun applyOptimisticPollAnswer(chatId: Long, messageId: Long, options: IntArray)
    fun clearPollPending(chatId: Long, messageId: Long, revert: Boolean)
    suspend fun toggleReaction(chatId: Long, messageId: Long, kind: ReactionKind, isChosen: Boolean): Boolean
    suspend fun setPollAnswer(chatId: Long, messageId: Long, options: IntArray): Boolean

    suspend fun resolvePublicHandle(handle: String): PublicHandleResult
    suspend fun resolveChatKind(chatId: Long): PublicHandleResult

    // ── Settings / Stats ─────────────────────────────────────────────
    val settingsStore: SettingsStore                 // exposed commonMain class
    val autoDownloadStore: AutoDownloadStore
    val statsRepository: StatsRepository             // surface used by SettingsScreen
    val translations: TranslationsStore?             // null on iOS

    // ── Auth / session ───────────────────────────────────────────────
    suspend fun logOut()
    val isAuthenticated: StateFlow<Boolean>

    // ── Reporting (CSAE) ─────────────────────────────────────────────
    suspend fun reportChat(chatId: Long, messageId: Long?, optionId: String?, text: String?): ReportState
    val reportDialogs: ReportDialogState
    val reportLogStore: ReportLogStore
    val reportExplainerStore: ReportExplainerStore
}
```

For `StatsRepository`, `SettingsStore`, `AutoDownloadStore` — when the *store* is commonMain (I-A) the backend can simply expose them as `val`s; the platform actuals just hand back the existing instances. Avoids inventing a parallel API.

iOS actuals stay no-op everywhere (empty flows, return false / null, throw nothing). Guest mode never enters any of these code paths.

### I-C — Screen migrations (~2 days)

Move in this order, one wave per session-bite to keep verification cheap:

| Wave | Files | What the move needs |
|---|---|---|
| 1 | `MainScaffoldDialogs`, `NavOverlayRenderer`, `TabContentSwitcher` | Already mostly KMP — they take `AppGraph` directly. Refactor each to take `backend: HortayBackend` + the specific stores they read. `BackEventCompat.EDGE_*` → `BackSwipeEdge.*`. `BackHandler` / `PredictiveBackHandler` → `androidx.compose.ui.backhandler.*`. |
| 2 | `MainScaffold` (557 lines) | The big one. Same pattern — backend + stores. The `PredictiveBackHandler` on the channel-stack overlay is the only non-trivial port; CMP 1.12 ships a `PredictiveBackHandler` in `ui-backhandler`. |
| 3 | `TimelineScreen`, `ChannelScreen`, `ChannelViewModel` | Take backend; drop direct `PostsRepository` / `CommentsRepository` / `ChannelActionsRepository` imports. ViewModels rewrite to call backend methods only. **This is the biggest UI bite — split across two commits if needed.** |
| 4 | `WebModeScaffold`, `AddChannelSheet`, `MigrationProposalSheet` | Web stuff — most deps already commonMain (web pipeline lives there). `MigrationProposalSheet` needs `MigrationCoordinator.progress` flow surfaced via backend. |
| 5 | `SettingsScreen`, `AutoDownloadScreen` | Lots of small dialogs. `Activity`-bound parts (status-bar tweak, language picker) flow through `StatusBarController` / `PlatformLanguagePicker` (I-A). |
| 6 | `ReportFlowSheet`, `ReportFlowViewModel`, `GuestReportDelegator`, `MediaShareActions`, `FullScreenMediaViewer`, `MediaViewerHost` | Reports + media viewer. `GuestReportDelegator` becomes a `LocalGuestReportDelegate: (target) -> Unit` slot; Android wires the existing impl. `MediaShareActions` becomes `PlatformImageSaver` (I-A). `FullScreenMediaViewer` + `MediaViewerHost` then move with no remaining Android imports. |
| 7 | `Theme.kt` | Last UI file — moves once `StatusBarController` is in place. |

**Refactor discipline.** Whenever a screen's signature changes from `graph: AppGraph` to `backend: HortayBackend + storeA + storeB`, follow up by checking the parameter count. If it exceeds ~6 and the screen really does need a slice of unrelated stores, introduce a screen-local `Deps` data class rather than threading 8 parameters by name. Don't pre-emptively design `Deps` — wait until a screen actually hits the threshold.

### I-D — iOS UI parity ship (~1 day)

Goal: `MainViewController.kt` mounts `WebModeScaffold` straight from commonMain instead of the parallel renderer.

1. `IosAppGraph` already builds the web pipeline. After I-C every store WebModeScaffold needs (`SubscriptionsStore`, `WebFeedSource`, `BookmarkStore`, `IgnoredChannelsStore`, `webRepository`, `webHttpClient`, `migrationStore`) is in commonMain. Add a stub `LocalGuestReportDelegate` provider (no-op on iOS).
2. Replace `MainViewController`'s 480-line parallel renderer with:
   ```kotlin
   fun MainViewController(): UIViewController = ComposeUIViewController {
       HortayTheme {
           CompositionLocalProvider(
               LocalMediaCache provides iosAppGraph.mediaCache,
               LocalCustomEmoji provides iosAppGraph.customEmoji,
               LocalVideoPlayerPool provides iosAppGraph.videoPlayerPool,
               LocalWebHttpClient provides iosAppGraph.webHttpClient,
               LocalPlatformToaster provides IosToaster(),
           ) {
               WebModeScaffold(
                   subscriptions = iosAppGraph.subscriptions,
                   webFeedSource = iosAppGraph.webFeedSource,
                   bookmarks = iosAppGraph.bookmarks,
                   ignoredChannels = iosAppGraph.ignoredChannels,
                   // … whatever WebModeScaffold ends up taking
                   backend = iosAppGraph.backend,            // the stub backend
                   onSignIn = { /* no-op stub on iOS; surfaces a snackbar */ },
               )
           }
       }
   }
   ```
3. iOS smoke test on Mac (Phase H rule 10 — Mac mini 192.168.88.142):
   ```bash
   ssh dvaderfun@192.168.88.142 'cd ~/hortay-android && git pull && rm -rf shared/build && \
     xcrun simctl uninstall booted dev.lyo.hortay 2>/dev/null; \
     xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
       -destination "platform=iOS Simulator,name=iPhone 15" build'
   ```
4. Add a real channel handle (`durov` works) → verify posts render with PostCard chrome, custom emoji animate via Compottie, photo albums display, channel chip on a post drills into the channel.

### I-E — Production polish (~half day)

- **Lint & detekt** — `./gradlew :androidApp:lintRelease :shared:detekt` clean. Detekt allowlist (composition locals, complexity thresholds) may need updates for the new commonMain density.
- **Baseline profile** — `./gradlew :androidApp:generateBaselineProfile` regenerates `baseline-prof.txt` / `startup-prof.txt`. Everything that previously referenced `dev.lyo.hortay.ui.media.LocalExoPlayerPool` etc. needs the new symbols.
- **CHANGELOG.md** — single `## [Unreleased]` paragraph: "Internal: codebase shares ~85% of files between Android and iOS; iOS app launches into guest mode with the same UI surface." Per project rules, no internal-narrative bullets; user-visible behaviour is identical, so the entry is one sentence.
- **ARCHITECTURE.md** — refresh the *Module map*, *Code distribution*, and *iOS UI* paragraphs to reflect 30/170/20-ish split. Add a row in *Load-bearing* for `LocalAvatarFileLoader` and `LocalStickerOutline` (the lambda slots).
- **Memory** — drop a project memory for the next session: "Phase I closed; commonMain is the dominant tree; TDLib iOS port is the only remaining work for full iOS authenticated mode."

---

## Verification gates

Each I-A / I-B / I-C wave / I-D / I-E commit must pass:

```bash
./gradlew :shared:compileAndroidMain
./gradlew :shared:compileKotlinIosSimulatorArm64
./gradlew :androidApp:assembleDebug
```

End of Phase I (full pass):

```bash
./gradlew :androidApp:lintRelease
./gradlew :shared:detekt
./gradlew :androidApp:installDebug      # smoke test on emulator
# on Mac mini:
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild ... && simctl install booted && simctl launch booted dev.lyo.hortay
```

**Acceptance criteria.** Open Hortay on the iOS simulator. Sign in fails (guest only). Tap "Continue without sign-in". Add `@durov`. Feed loads with full PostCard chrome — channel header, formatted text, photo album grid, animated custom emoji via Compottie, reactions, view count, forward chip, link previews. Tap a channel chip → channel screen opens with header + history. Tap a post body → snackbar explaining comments need sign-in. Settings opens. Hidden channels works. Add channel sheet works. Migration prompt does not surface (guest mode). Nothing crashes.

Side-by-side on Android emulator + iOS simulator: visual diff should reveal only deliberate platform deltas (system status bar style, iOS sheet drag handle, iOS predictive-back-by-edge-swipe vs Android predictive-back).

---

## Open questions deferred to Phase II

- **TDLib iOS port.** The single remaining gap to true parity. Cross-compile `libtdjni` for iOS via the existing Docker build, write cinterop `.def` files for ~200 `TdApi.*` types, port `TdClient.kt` to cinterop, flip `HortayBackend.ios.actual` from the stub to the real impl. Multi-week. **Not in scope for Phase I.**
- **Wear OS target.** Decide before committing to module-split that would either help or hurt Wear.
- **WebAssembly / Desktop targets.** Almost free after Phase I (everything in commonMain already targets iosMain + androidMain; Wasm needs only a `wasmJsMain` source set + Js Ktor client). Useful for a web demo.
- **Compottie WebP animation.** Verify whether Compottie supports animated WebP; if so, migrate WebM custom emojis from the static-thumb fallback to Compottie's poster path on iOS.

---

## Critical decisions discovered in Phase H — DO NOT relitigate

1. **Direct CMP Maven coords, never the `compose.*` DSL.** Material 3 Expressive symbols are stripped from the DSL's iOS klib variant. (Documented in Phase H plan.)
2. **`compose-multiplatform = 1.12.0-alpha01`.** Alpha02+dev4168 brought no Expressive benefit and added churn. Stay on alpha01 stable.
3. **`-Xexpect-actual-classes` + `-Xskip-prerelease-check`.** Load-bearing for clean output. Don't remove.
4. **`kotlin.time.Clock` / `kotlin.time.Instant`** (Kotlin 2.x stdlib), not `kotlinx.datetime.Clock`. The latter was removed from kotlinx.datetime 0.7.
5. **Native counters / locks via `kotlinx.atomicfu`** for hot paths. Cheaper than `Mutex.withLock`.
6. **`androidx.graphics.shapes 1.1.0` is KMP**, but its `toPath()` returns `android.graphics.Path`. Walk cubics manually via `RoundedPolygon.cubics` / `Morph.asCubics` — example in `commonMain/ui/theme/Shape.kt::MorphShape::createOutline`.
7. **JVM-only formatters are forbidden in commonMain.** `String.format("%.1f", …)`, `Map.toSortedMap`, `Map.putIfAbsent`, `java.util.UUID.randomUUID`, `java.util.concurrent.TimeUnit`, `System.currentTimeMillis`, `java.text.NumberFormat` — every one has a KMP-safe inline replacement already proven in this codebase (manual `padStart`, `entries.sortedBy { it.key }`, `if (k !in m) m[k] = v`, atomicfu counter, `kotlin.time.Duration`, `nowMs()`, `formatThousandsKmp` / `formatSubscribers`).
8. **`androidx.compose.runtime.Immutable` works in commonMain via CMP.** Don't substitute with anything custom.
9. **iOS Kotlin compile works on Windows.** `:shared:compileKotlinIosSimulatorArm64` is the cheap iOS validator; link + Xcode bundle are Mac-only.
10. **Don't bulk-move 30+ files at once.** Move in waves of 5–15, verify between each. Reverting one file is cheap; reverting a half-failed bundle is messy.
11. **When a moved file breaks the build, revert before fixing.** `git mv` it back, fix the dependency (extract the data class, define the expect/actual, add a lambda slot), re-move.
12. **Lambda slots (`CompositionLocal` of a function type) > full expect/actual classes** when the surface is a single suspending call. Example: `LocalStickerOutline = staticCompositionLocalOf<suspend (Int) -> Path?>` replaced a whole expect class. Saves files and is less code.

---

## Reference projects

- **Univera multiplatform mobile** at `C:\GitHub\univera\univera-multiplatform-mobile` — the project that taught Phase H about direct CMP Maven coords. Same CMP version.
- **Compose Multiplatform 1.12.0-alpha01 release notes** — <https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0-alpha01>.
- **`androidx.compose.ui.backhandler`** — CMP-shipped commonMain `BackHandler` + `PredictiveBackHandler`. Added in Phase H late (commit landed alongside `WebSearchScreen` move). Use this in I-C wave 1 onwards.
- **Material3 KLIB API surface** — `gh api repos/JetBrains/compose-multiplatform-core/contents/compose/material3/material3/api/material3.klib.api?ref=jb-main` reveals what's exposed on iOS. Re-check before assuming anything is stripped.
- **Mac mini SSH** — `192.168.88.142` (`dvaderfun` / `1SideDark1`). Set up `ssh-copy-id` before the first I-D run so the rebuild cycle is unattended.
