<!--
Maintainer notes:
- INDEX, not tutorial. Setup / "what this is" → README.md. Rationale → header KDoc next to code.
- Every rule must be verifiable: "Use X" / "Don't Y", not "be mindful of X".
- 3+ sentences of rationale = move it into a code comment.
-->

# Architecture

> 🚧 **ACTIVE MIGRATION:** **BuildKonfig** (Config bridge replacement). Rules against this tool below are actively being phased out. (Koin DI: done — Phase I.5. Navigation 3: done — see "Hard rules → Architecture & DI".)

Modules, load-bearing decisions, hard rules, and conventions. Pair with [README.md](README.md) for setup.

## Language policy

- **Code, comments, identifiers, commit messages — English only.**
- **User-facing strings — never hardcoded.** `strings.xml` (default English) + `values-uk/strings.xml` mirror, in the same commit. Use `<plurals>` (UK: one/few/many/other; EN: one/other).
- **Talk to the user in their language** (UA/PL/EN/…). Code stays English.

## Two modes

1. **Authenticated (TDLib)** — full MTProto. Persistence is TDLib's own (`useFileDatabase` / `useChatInfoDatabase` / `useMessageDatabase = true`).
2. **Guest / anonymous** — read `t.me/s/<u>` without credentials. Persistence in `web.db` (SQLDelight). Activated via `GuestModeStore` flag.

Single-process, single-Activity. `MainActivity` routes: `auth.Ready → MainScaffold` → `isGuest → WebModeScaffold` → else `AuthScreen`. Subscriptions (DataStore `SubscriptionsStore`) survive both transitions.

## Architecture (4 modules — KMP/CMP)

- **`:androidApp`** — Android application entry point. `HortayApp` (Application class), `MainActivity`, `AndroidManifest.xml`, signing configs, build types (debug/release/beta/benchmark), proguard rules. Depends on `:shared`. Plugin: `com.android.application`. JVM 21.
- **`:shared`** — KMP library module with Compose Multiplatform UI. Contains `AppGraph` (manual DI), repositories, ViewModels, all UI, data layer, SQLDelight DAOs. Plugin: `com.android.kotlin.multiplatform.library` + `org.jetbrains.kotlin.multiplatform` + `org.jetbrains.compose`. Source sets: `commonMain` (shared across all targets), `androidMain` (Android-specific incl. TDLib + ExoPlayer). JVM 21. Naming follows the [May 2026 JetBrains KMP default structure](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/) — `shared` denotes a library, `androidApp` is the entry point.
- **`:libtdlib`** — Vendored TDLib JNI (`org.drinkless.tdlib.{Client,TdApi}.java`) + `jniLibs`. Android-only. Don't hand-edit the `.java` files — `scripts/update-tdlib.sh` will clobber them.
- **`:baselineprofile`** — Macrobenchmark, AOT cold-start profile. Targets `:androidApp`.

**Why split `:androidApp` + `:shared`:** AGP 9 forbids `com.android.application` + `org.jetbrains.kotlin.multiplatform` in the same module. Application module stays pure Android; KMP library module owns shared code. See [JetBrains migration guide](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html).

**BuildConfig bridge.** `com.android.kotlin.multiplatform.library` doesn't generate `buildConfigField`. `:androidApp` defines BuildConfig fields (`TELEGRAM_API_ID`, etc.), `HortayApp.onCreate` writes them into the `AppConfig` object in `:shared`/androidMain. All non-Application code reads from `AppConfig`.

**Namespace split.** `:shared` namespace = `dev.lyo.hortay` (so `R.string.*` references stay unchanged across 130+ files). `:androidApp` namespace = `dev.lyo.hortay.app` (avoids R-class collision). Manifest uses FQCN `android:name="dev.lyo.hortay.HortayApp"` because `HortayApp` and `MainActivity` live in `androidApp/src/main/kotlin/dev/lyo/hortay/` — manifest's namespace-relative resolution would otherwise look in `dev.lyo.hortay.app.*`.

DI is **Koin 4.x** (`shared/src/commonMain/kotlin/dev/lyo/hortay/di/`). `HortayApp.onCreate` calls `startKoin { modules(coreModule, storesModule, webModule, tdlibModule, uiBridgeModule, viewModelModule, platformModule()) }`; iOS calls `initKoin()` from `MainViewController`. Eager singletons (`tdClient`, `lifecycleBridge`, `autoDownloader`, `webFeedScheduler`, `webCustomEmojiBridge`, `migrationCoordinator`, `logoutCleanup`) are flagged `createdAtStart` so their `bind()` side-effects run before any consumer touches them — same ordering contract the historical hand-wired `AppGraph` enforced via property declaration order. `MainActivity` / `MainViewController` resolve top-level singletons via `koinInject<X>()`; inner composables keep explicit params (Compose best practice — explicit data flow). Heavy singletons (`MediaCache`, `CustomEmoji`, `VideoPlayerPool`, etc.) are still wired through CompositionLocal at the entry point to keep the deep PostCard chain off long parameter lists.

**Code distribution (post-Phase I.5, post-nav3):** ~49 files in `shared/src/androidMain/kotlin/`, ~180 files in `shared/src/commonMain/kotlin/` (includes the new `di/` modules), ~20 files in `shared/src/iosMain/kotlin/`. Every UI screen — `MainScaffold`, `TabContentSwitcher`, `TimelineScreen`, `ChannelScreen`, `CommentsScreen`, `WebModeScaffold`, `SettingsScreen`, `AutoDownloadScreen`, `ReportFlowSheet`, `MediaShareActions` + `FullScreenMediaViewer`, `MainScaffoldDialogs` — lives in commonMain. Nav substrate is nav3's `NavDisplay` mounted inside `MainScaffold` / `WebModeScaffold` (the bespoke `NavOverlayRenderer` is gone). androidMain is the irreducible core: `HortayApp` + `tdlibModule` + TDLib-bound repositories (TdClient, PostsRepository, CommentsRepository, ChannelActionsRepository, MessageMapper, CustomEmojiRepository, StatsRepository, MigrationCoordinator, LogoutCleanup, …) + platform actuals (Coil disk cache, status bar, language picker, ConnectivityManager data-saver probe). The platform-agnostic surface lives on `HortayBackend` (commonMain expect/actual) plus a small set of `Local*` CompositionLocal slots (`LocalMediaShareActions`, `LocalLanguagePicker`, `LocalGuestReportDelegate`, `LocalStickerOutline`, `LocalAvatarFileLoader`, `LocalStatusBarController`).

**iOS UI (post-Phase I):** `shared/src/iosMain/kotlin/dev/lyo/hortay/MainViewController.kt` mounts the same `WebModeScaffold` tree the Android guest-mode path renders. Feature parity: full PostCard chrome (avatars, formatted text, photo albums, animated custom emoji via Compottie, reactions, view count, forward chip, link previews), channel drill-down, comments overlay (with the guest-mode "Sign in" empty-state), bookmarks, settings, predictive back. The TDLib-bound paths short-circuit to no-ops via the stub `HortayBackend.ios.kt` — guest UI never reaches them. `iosApp/iosApp.xcodeproj` builds + runs on Mac/Simulator (Xcode 16+). Phase II will wire the real TDLib backend once `libtdjni` is cross-compiled for Apple.

**Modularization trigger (for further splits within `:shared`).** Stay single-source-set inside `:shared` until any of: > 300 Kotlin files in `androidMain`, cold build > 60 s on dev hardware, or > 1 active contributor. Cut lines are already encoded by packages — `data/web/*` → `:data-web`, `ui/timeline/*` + `ui/main/*` → `:feature-timeline`, `ui/theme/*` + `ui/components/*` → `:core-ui`. Until then, enforce boundaries with `internal` visibility, not separate modules.

## Load-bearing — don't change without reading the rationale in place

| What | Rationale lives in | TL;DR |
|---|---|---|
| TDLib two-stage update pipeline | `data/TdClient.kt:71-89` | UNLIMITED Channel → SharedFlow(64). |
| MediaCache single-coroutine reducer | `data/MediaCache.kt:125-138` | `fileEvents` Channel = one writer. |
| MediaCache stall watchdog | `data/MediaCache.kt:149-178` | 3 regimes; skip under `WaitingForNetwork`. |
| PostsRepository concurrency | `data/posts/PostsRepository.kt` (refreshMutex KDoc) | `refreshMutex` serialises batch refresh; live ingest runs OUTSIDE mutex via `MutableStateFlow.update` CAS-loop. Lambdas to `_posts.update {}` MUST be pure functions of the snapshot. |
| PostsRepository cold-start contract | `data/posts/PostsRepository.kt:refreshLocked` | Harvest `Chat.lastMessage` from `chatCache`. **Don't reintroduce `GetChat × N` / `GetChatHistory × N`** — FLOOD_WAIT class. |
| Compose stability chain | `data/PostContent.kt`, `TimelinePost.kt` | `@Immutable` end-to-end. |
| Cold-start snapshot | `data/TimelineSnapshotStore.kt` + `TimelineViewModel:59-66` | Restore → parallel `refreshIfStale`. |
| FLOOD_WAIT global gate | `data/TdClient.kt:100-113` | Single `AtomicLong` deadline. Recognise **both 420 and 429**. |
| TDLib quirks (album sync, stall) | `data/MediaCache.kt:55-71` + `data/posts/PostsRepository.kt:67-74` | `tdlib/td#2523`, `tdlib/td#2585`. |
| Web-mode SQL portability | `shared/src/androidMain/sqldelight/.../web/db/*.sq` | All upserts via `INSERT OR IGNORE` + `UPDATE` — **not** `ON CONFLICT DO UPDATE`. Android 8/9 SQLite < 3.24. FTS5 skipped. |
| Web-mode media TTL | `data/web/Post.sq` + `WebFeedSource.DEFAULT_MEDIA_TTL_MS` | t.me/s/ CDN URLs live 1–4 h. |
| Guest-mode routing | `MainActivity.kt` | `auth.Ready → MainScaffold` → `isGuest → WebModeScaffold` → `AuthScreen`. |
| StartupCoordinator | `data/StartupCoordinator.kt` | `Booting → Active` gates speculative work. |
| Channel-drill as overlay | `ui/main/MainScaffold.kt` | `nav3.NavDisplay` mounted above always-mounted `TabContentSwitcher`; scenes auto-render previous-layer peek during predictive-back. |
| ReadCursors / OldestUnreadFirst | `data/ReadCursors.kt`, `ui/timeline/LocalReadCursors.kt` | `PersistentMap` + CompositionLocal; snapshot frozen at refresh boundaries. |
| Tap-navigation contract | `data/TapNavigation.kt`, `ui/main/MainScaffold.kt` (`pushChannel`, `CHANNEL_PUSH_PREFETCH_TIMEOUT_MS`) | **Channels:** push AWAITS `loadChannelHistory` up to 400 ms, then mounts `NavTarget.Channel` (warm/cache opens stay instant via cooldown short-circuit). **Comments:** still fire-and-forget. Destination-side `SCREEN_MOUNT_GRACE_MS` (120 ms × `ValueAnimator.getDurationScale()`) suppresses skeleton flicker as a backstop. The channel exception exists because the cold-start `Chat.lastMessage` harvest leaves the slice with exactly one post per channel; in OldestUnreadFirst (asc-by-date) those 79 older posts merging in above the visible row read as a "stretching" jump that the destination-side gate alone couldn't hide. |

## Critical identifiers

| Identifier | Why it's load-bearing |
|---|---|
| `dev.lyo.hortay` (+ `.beta`) | applicationId, namespace, signing identity. |
| `org.drinkless.tdlib` | TDLib upstream FQCN. Renaming breaks JNI symbol lookup in libtdjni.so. |
| Release keystore (`storeFile` + `keyAlias` from `keystore.properties`) | Release signing identity. Losing it = losing the upgrade path for installed users. |
| Koin module set (`coreModule`, `storesModule`, `webModule`, `tdlibModule`, `uiBridgeModule`, `viewModelModule`, `platformModule()`) | Process-singleton DI root. `startKoin {}` runs once in `HortayApp.onCreate` / `MainViewController`'s `initKoin()`. |
| `LocalMediaCache` / `LocalCustomEmoji` / `LocalVideoPlayerPool` / `LocalReadCursors` | CompositionLocal heavy-singleton injection. |

## Hard rules

### Architecture & DI

Each `❌` carries a **Revisit:** clause — the concrete condition that would justify reopening the decision. Without that condition, the answer is no.

- ✅ **Koin 4.x for DI.** Modules under `shared/src/commonMain/kotlin/dev/lyo/hortay/di/`. Entry points (`MainActivity`, `MainViewController`) use `koinInject<X>()`; inner composables keep explicit params (no `koinInject` deep in the tree — explicit data flow is the Compose-recommended pattern and helps `@Preview`). ViewModels via `koinViewModel(parameters = { parametersOf(...) })`. Eager singletons (`tdClient`, `lifecycleBridge`, `autoDownloader`, schedulers, `logoutCleanup`) flagged `createdAtStart = true` to preserve the historical ordering contract.
- ❌ Firebase / Crashlytics / Sentry / analytics / phone-home. INTERNET is for TDLib + anonymous `t.me/s/` only. **Revisit:** never — privacy-as-feature.
- ❌ Room. SQLDelight 2.3 owns `web.db`; TDLib owns its own. **Revisit:** never (no shared scope).
- ❌ OkHttp / Retrofit / Ktor as a general HTTP client. Coil pulls `coil-network-okhttp` for images, OkHttp is declared directly only for the web-mode `t.me/s/` pipeline + custom-emoji JSON. **Revisit:** if a typed REST backend joins the stack (none planned).
- ❌ FCM / push. TDLib `RegisterDevice` + `UpdateNotification`. **Revisit:** if TDLib push proves unreliable in field reports.
- ❌ ViewBinding / Fragments. Compose-only, single-Activity. **Revisit:** never.
- ✅ **Navigation 3 (KMP) for nav substrate.** `androidx.navigation3:navigation3-runtime` (raw androidx — KMP-published with iosarm64 + iossimulatorarm64 variants) + `org.jetbrains.androidx.navigation3:navigation3-ui` (JetBrains KMP fork of `NavDisplay` + `Scene` / `SceneStrategy`) + `androidx.lifecycle:lifecycle-viewmodel-navigation3` (per-entry `ViewModelStoreOwner` decorator). [`NavStack`](shared/src/commonMain/kotlin/dev/lyo/hortay/data/NavStack.kt) stays the Koin singleton; it now wraps a `SnapshotStateList<NavTarget>` (the back-stack nav3 reads). `NavTarget : NavKey` (sealed: `Channel` / `Comments` / `WebChannel`). Per-target screens live inside `entryProvider { entry<NavTarget.X>(clazzContentKey = { it.entryId }) { ... } }` in `MainScaffold` and `WebModeScaffold`; `entryId` is the contentKey so two `Channel(123L)` pushes never collapse. Predictive-back: nav3's `NavDisplay` owns the gesture at stack depth ≥ 2 (default Material transition specs); a plain `BackHandler(enabled = topEntry != null && stack.size <= 1) { nav.pop() }` catches the stack-of-1 case where nav3 disables itself. **Revisit:** if the JetBrains lifecycle KMP fork stops publishing `lifecycle-viewmodel-navigation3` under raw `androidx.*`. **Don't** replace the Koin singleton with `rememberNavBackStack`: `DeepLinkRouter` submits before Compose mounts, and the same `NavStack` must survive `MainScaffold ↔ WebModeScaffold` swap on guest-mode toggles.
- ✅ SQLDelight 2.3 for `web.db` only. TDLib mode runs without a DB.

### TDLib usage

- ❌ Direct `client.send` from UI / Composable. Go through a repository (FLOOD_WAIT gate + UserMessageBus error routing live there).
- ❌ `GetChat × N` / `GetChatHistory × N` per-channel fan-out on cold-start. 200 channels × `GetChatHistory(80)` = instant FLOOD_WAIT. On-demand paths (`loadChannelHistory`, `loadOlder`, `loadHistoryAround`) are fine.
- ❌ Parsing FLOOD_WAIT from error message strings. `TdClient` handles it centrally (420 + 429) — extend the helper if needed.
- ❌ Hand-editing `libtdlib/.../{Client,TdApi}.java` — vendored upstream.
- ❌ `LOG_VERBOSITY > 1`.
- ✅ `OpenChat` / `CloseChat` / `ViewMessages` go through `ChatPresence`. Wrap critical pairs in `NonCancellable` (`tdlib/td#2312`).
- ✅ Every session-scoped state holder subscribes to `TdClient.loggedOut.collect { clear() }`. Includes process-wide sets and Composable state.

### Compose

- ❌ `rememberSaveable` for top-level navigation (`selectedTab`, `channelStack`). Cold launch must land on Home top-of-feed.
- ❌ Literal `tween(...)` for transitions. Use `MotionScheme.{default,fast}{Spatial,Effects}Spec()`.
- ❌ Stripping `@Immutable` from `TimelinePost` / `PostContent` graph. Silent skippability regression.
- ❌ Substituting `PersistentList` / `PersistentMap` with `List` / `Map` "for simplicity".
- ❌ Passing heavy singletons as Composable params (caused constructor explosion on 600-row PostCard). Use CompositionLocal.
- ❌ Blocking comments / user-profile / other non-channel pushes on a prefetch. The destination-side anti-flicker grace (`SCREEN_MOUNT_GRACE_MS`, 120 ms) is the right tool for those — the original sender slot is invisible past the slide-in so the prefetch has free runway. **Exception: channel pushes** await `loadChannelHistory` up to `CHANNEL_PUSH_PREFETCH_TIMEOUT_MS` (400 ms) because OldestUnreadFirst + cold-start `Chat.lastMessage` harvest leaves the slice with one post — without the await, 79 older posts merge in above the visible row mid-frame and the user reads it as a "stretching" jump. See `ui/main/MainScaffold.kt` `pushChannel`.
- ❌ Hardcoding a skeleton-grace number per screen. Screen-mount loading states use `rememberDeferredLoading(graceMs = SCREEN_MOUNT_GRACE_MS)` (120 ms, anti-flicker); media file-IO uses the default `LOADING_OVERLAY_GRACE_MS` (600 ms, longer latency budget). Two calibrated constants, not knobs to retune ad-hoc.
- ❌ Push-side flags on `NavTarget` that override screen-side loading (e.g. `preloadTimedOut`, `instantSkeleton`). They leak push logic into the model and turn one decision into two — the screen already has all the data it needs (its own state) to decide.
- ✅ Material 3 Expressive: `MaterialExpressiveTheme` + `MotionScheme.expressive()`.
- ✅ Predictive back: `PredictiveBackHandler` + `Animatable` + `graphicsLayer`. Only one handler `enabled = true` at any time.
- ✅ Lambdas in `LazyColumn`/`LazyRow` items wrapped in `remember(...)` with stable keys. Inline `{ … }` capturing non-stable scope breaks skipping under scroll.
- ✅ Read state: `ReadCursors` is the single source of truth, consumed via `LocalReadCursors`. Cursors are monotonic — clamp on every seed/update (`if (new > existing) put`) to survive logout/login races.
- ✅ `rememberDeferredLoading` scales its grace by `ValueAnimator.getDurationScale()` via `effectiveSkeletonGrace` — reduced motion (system / developer options) collapses the grace to 0 and paints the skeleton on the first Loading frame.

### i18n & a11y

- ❌ Hardcoded user-facing strings. Always `values/strings.xml` + `values-uk/strings.xml` in the same commit.
- ❌ Replying to the user in English when they write in another language.
- ✅ `<plurals>` for counts. `contentDescription` via `stringResource(...)`.
- ✅ Every clickable Row/Box that isn't `IconButton`/`Button` gets `Modifier.clickable(role = Role.Button)` + meaningful `contentDescription`.

### Build & release

- ❌ `enableV1Signing = true` — AGP 9 + R8 zip layout breaks JarInputStream v1.
- ❌ `x86_64` in release `abiFilters` — +24 MB libtdjni.so for zero users.
- ❌ Bumping `versionCode` by hand. It's auto-derived from `git rev-list --count HEAD` in `androidApp/build.gradle.kts`.
- ❌ `bundleRelease` without a fresh commit — same versionCode → Play returns 409. Workflow: commit → bundle.
- 🚧 **MIGRATION TO BUILDKONFIG**: We are adopting `com.codingfeline.buildkonfig` to replace the manual `AppConfig` bridge from `:androidApp` to `:shared`.
- ❌ `com.android.application` plugin + `org.jetbrains.kotlin.multiplatform` in the same module. AGP 9 forbids it. App-shell stays separate from KMP library.

### Workspace

- ❌ New `.md` files without an explicit user request. README + CHANGELOG + ARCHITECTURE + SECURITY is the full set.
- ❌ TODO comments, commented-out dead code, debug `println`s.
- ❌ Compressing header KDocs that say "tried X, broke Y" — they're load-bearing for onboarding.
- ✅ Conventional commits, scope = package: `feat(timeline):`, `perf(media):`, `build(beta):`.
- ✅ `@Immutable` / `@Stable` on every data class that reaches Compose.

### Changelog

CHANGELOG.md is release notes for a user, not a PR description. Rationale lives elsewhere (commit body, load-bearing KDoc, this file). Aggressive editing of entries is OK — write the entry as it should be read on release day, not as a stream of consciousness during the fix.

- ❌ Multi-sentence paragraphs per bullet. One sentence, one change. Two sentences max only if the second is "no behaviour change" / "see CommitX for rationale".
- ❌ File paths, line numbers, function names in bullets (`PostsRepository.refreshLocked`, `TdClient.kt:71-89`). Internal — belongs in commits / KDoc.
- ❌ TDLib / Android issue links, "per Aliaksei Levin on tdlib/td#N" citations, RFC numbers. Internal.
- ❌ "Two compounding root causes…", "the previous fix…", post-mortem narrative. Internal.
- ❌ Reformulating from the developer's POV ("fixed bug in foo()"). Use the user's POV ("X works again" / "Y no longer Z").
- ✅ Categories in Keep-a-Changelog order: **Added** → **Changed** → **Fixed** → **Performance** → **Architecture** → **Build**. Skip empty ones.
- ✅ English; UI strings keep their original glyphs (`⭐`, `→`, `↓ N`, `@handle`).
- ✅ New entries go under `## [Unreleased]`. On release: rename `[Unreleased]` to `## [X.Y.Z] — YYYY-MM-DD` (em-dash, ISO date) and start a fresh `## [Unreleased]` block on top.
- ✅ When a single fix sentence loses load-bearing context, point at the durable home (`ARCHITECTURE.md → "Load-bearing"`, the file's KDoc, the commit) — never re-explain inline.
- ✅ Before adding a bullet under `[Unreleased]`, check it isn't already there in a different phrasing. Merge variants of the same user-visible change.

## Commands

```bash
./gradlew :androidApp:installDebug
./gradlew :androidApp:assembleRelease           # release APK (needs keystore.properties)
./gradlew :androidApp:assembleBeta              # beta, applicationId.beta, versionCode = git commit count
./gradlew :shared:compileAndroidMain        # compile KMP shared module only
./gradlew test                                  # JUnit 5 unit tests
./gradlew :androidApp:lintRelease               # R8 + lint vital — pre-commit gate
./gradlew :androidApp:generateBaselineProfile   # AOT profile (~3–5 min on device)
./scripts/update-tdlib.sh [SHA]                 # Bump TDLib (Docker, ~10–15 min)
adb logcat -s TdClient MediaCache PostsRepository ChatPresence
adb shell run-as dev.lyo.hortay tail -f files/td-logs/td.log  # TDLib internal log (debug builds, LOG_VERBOSITY=1)
```

Toolchain: JDK 21, Gradle 9.5.1, AGP 9.2.0, Kotlin 2.3.10 (K2), Compose Multiplatform 1.12.0-alpha01. Compose Compiler via `org.jetbrains.kotlin.plugin.compose`.

### Verifying rules

- **Compose skippability** — Compose Compiler stability reports are wired via the Kotlin Compose plugin; check `shared/build/compose_compiler/` after a build. New `@Stable`/`@Immutable` regressions show up as "unstable" classes in the graph.
- **Translations parity** — `./gradlew :androidApp:lintRelease` flags `MissingTranslation`. CI gate.
- **Cold-start budget** — `:baselineprofile` macrobenchmark + `adb logcat -s PostsRepository` (look for `GetChat`/`GetChatHistory` storms).
- **Static analysis (Compose stability + Kotlin smells)** — `./gradlew :shared:detekt` (config: `config/detekt/detekt.yml`, baseline: `config/detekt/baseline.xml`). Not bundled into `lintRelease` — heavy on dev hardware; CI gate adds it explicitly.

## Setup delta on top of README

- `keystore.properties` at the repo root (gitignored) supplies `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. AGP enables release signing only when this file exists (`androidApp/build.gradle.kts`).
- `local.properties` at repo root (gitignored) supplies `telegram.apiId` + `telegram.apiHash` from <https://my.telegram.org>. Read by `androidApp/build.gradle.kts` → `BuildConfig` → `AppConfig`.
- Beta uses the same keystore + auto-versionCode from git.
- `gradle.properties` carries `HORTAY_CHILD_SAFETY_POLICY_URL` / `HORTAY_PRIVACY_POLICY_URL` for CSAE compliance.

## Versioning

- `versionCode` for release and beta is auto-derived from `git rev-list --count HEAD` in `androidApp/build.gradle.kts`.
- `versionCode = 1` in `defaultConfig` is a sentinel for debug builds.
- `versionName` is manual. Bump on semver-worthy releases. Beta auto-appends `-beta-<sha>`.
- TDLib pin: `scripts/tdlib-version.txt` (auto-generated). Dedicated commit `chore(tdlib): bump to <sha>` per bump.
- Native debug symbols: `scripts/update-tdlib.sh` (default `KEEP_DEBUG=1`) extracts unstripped libs into `libtdlib/build/tdlib-unstripped/<abi>/libtdjni.so`. AGP `debugSymbolLevel = "FULL"` packages them into the AAB. `libtdlib/build.gradle.kts` sourceSets picks unstripped overlay when present, falls back to committed stripped libs otherwise (AGP 9 forbids duplicate `.so` across `srcDirs`).
