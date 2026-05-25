<!--
Project instructions for Claude Code and other coding agents.
Human-facing docs: README.md (setup), ARCHITECTURE.md (decisions), CHANGELOG.md (history).
-->

@ARCHITECTURE.md
@README.md
@CHANGELOG.md

## Agent rules of engagement

- Talk to the user in their language (UA / PL / EN / …). Code, comments, identifiers, commit messages — English only.
- User-facing strings — never hardcoded. `values/strings.xml` (default English) + `values-uk/strings.xml` mirror in the same commit. Plurals via `<plurals>` (UK: one/few/many/other; EN: one/other).
- Architecture, hard rules, load-bearing decisions, and build/release conventions live in `ARCHITECTURE.md`. Read before suggesting non-trivial changes.
- Conventional Commits with package scope: `feat(timeline):`, `fix(media):`, `build(beta):`, etc.
- No new `.md` files without an explicit user request. README + CHANGELOG + ARCHITECTURE + SECURITY is the full set.

## Module map (KMP / CMP, post-migration)

- `:androidApp` — Android application shell. `HortayApp`, `MainActivity`, manifest, signing, build types, proguard. Depends on `:shared`. Plugin: `com.android.application`.
- `:shared` — KMP library with Compose Multiplatform UI + data layer. Plugins: `kotlin.multiplatform` + `com.android.kotlin.multiplatform.library` + `org.jetbrains.compose`. Targets: `android()`, `iosArm64()`, `iosSimulatorArm64()`. Source sets: `commonMain` (shared), `androidMain` (Android-specific), `iosMain` (iOS-specific actuals + `MainViewController` entry). Naming per [May 2026 JB KMP default structure](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/).
- `:iosApp` — iOS application shell. Xcode project (`iosApp.xcodeproj`). `iOSApp.swift` SwiftUI entry, `ContentView.swift` wraps `MainViewControllerKt.MainViewController()` from the shared framework via `UIViewControllerRepresentable`. Xcode build phase calls `:shared:embedAndSignAppleFrameworkForXcode`. **Mac + Xcode 16+ required to build.**
- `:libtdlib` — TDLib JNI (Android-only; iOS port deferred — see "TDLib iOS strategy" in ARCHITECTURE.md).
- `:baselineprofile` — macrobenchmark.

Current code distribution (post-Phase I.5, post-nav3): **~49 files in `androidMain`, ~180 in `commonMain` (includes the new `di/` modules), ~20 in `iosMain`**. Every UI screen lives in commonMain — `MainScaffold`, `TabContentSwitcher`, `TimelineScreen`, `ChannelScreen`, `CommentsScreen`, `WebModeScaffold`, `SettingsScreen`, `AutoDownloadScreen`, `ReportFlowSheet`, `MediaShareActions` + `FullScreenMediaViewer`, `MainScaffoldDialogs`. Nav substrate: nav3 `NavDisplay` (see `ARCHITECTURE.md` rule on Navigation 3); the old `NavOverlayRenderer` is gone. DI is Koin 4.x — `di/` modules in commonMain (`coreModule`, `storesModule`, `webModule`, `viewModelModule`, `uiBridgeModule`), per-platform `tdlibModule` (androidMain) / `tdlibStubModule` (iosMain), `platformModule()` `expect/actual`. androidMain is the irreducible core: `HortayApp` + `tdlibModule` + TDLib-bound repositories + `LogoutCleanup` + platform actuals (Coil disk cache, status bar, language picker, ConnectivityManager data-saver probe). iOS guest mode mounts the SAME `WebModeScaffold` tree the Android guest-mode path renders.

## Build commands

```bash
./gradlew :androidApp:installDebug
./gradlew :androidApp:assembleDebug
./gradlew :shared:compileAndroidMain                   # KMP library only
./gradlew :shared:compileKotlinIosSimulatorArm64       # iOS Kotlin compile (works on Windows!)
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64  # iOS framework link (Mac only)
./gradlew :androidApp:lintRelease                      # pre-commit gate
./gradlew :shared:detekt                               # static analysis
./scripts/update-tdlib.sh                              # bump TDLib (Docker, ~10-15 min)
```

iOS build on Mac:
```bash
open iosApp/iosApp.xcodeproj  # then Cmd+R to run on iPhone Simulator
```
The Xcode "Compile Kotlin Framework" build phase invokes `:shared:embedAndSignAppleFrameworkForXcode` automatically.

`local.properties` (gitignored) must have `telegram.apiId` + `telegram.apiHash` from <https://my.telegram.org>. `keystore.properties` (gitignored) needed for release/beta builds only.

## Migration gotchas

- **Don't reference `dev.lyo.hortay.BuildConfig` from `:shared`.** KMP library plugin doesn't generate BuildConfig. Use `AppConfig.*` (defined in `shared/src/androidMain/kotlin/dev/lyo/hortay/AppConfig.kt`). New runtime constants go into `androidApp/build.gradle.kts` as `buildConfigField`, then `HortayApp.onCreate` mirrors them into `AppConfig`.
- **Don't add `com.android.application` to `:shared`.** AGP 9 forbids it alongside KMP plugin. App-shell stays in `:androidApp`.
- **Manifest uses FQCN** (`android:name="dev.lyo.hortay.HortayApp"`) — not `.HortayApp`. Namespace-relative resolution would look in wrong package because `:androidApp` namespace is `dev.lyo.hortay.app`.
- **AGP 9 + dual jniLibs `srcDirs` = duplicate-resource error.** `libtdlib/build.gradle.kts` picks unstripped overlay when present, falls back to committed stripped libs otherwise.
- **AndroidUnitTest source set is `androidHostTest`** under `com.android.kotlin.multiplatform.library` plugin (not `androidUnitTest`). Requires `kotlin { android { withHostTest {} } }`.
- **Compose deps in androidApp need Compose BOM.** Library exposes via `implementation`, not `api`, so transitive deps don't reach androidApp. BOM-managed `compose.ui.tooling*` requires the platform reference.
- **CMP `composeCompiler { reportsDestination / metricsDestination }` breaks iOS targets on Windows.** Compose compiler's JsonBuilder writes filenames with colons (target-name-derived), invalid on NTFS. Disable the destinations in `shared/build.gradle.kts` or scope per-target. Stability config (`stabilityConfigurationFiles.add(...)`) still works.
- **iOS Kotlin compile works on Windows** (`compileKotlinIosSimulatorArm64`). The Apple toolchain is only required for **link** (`.framework` binary) + Xcode bundle. Use this to validate iOS sources cross-platform during development.

## KMP library audit (per `gradle/libs.versions.toml`)

- **Replaced (KMP-native, done):**
  - `org.jsoup:jsoup` → `com.fleeksoft.ksoup:ksoup` 0.2.6 — `WebTextRenderer`, `WebPostAdapter`, `TmePageParser`. Drop-in API.
  - `okhttp` direct usage → `io.ktor:ktor-client-*` 3.5.0. Engines: `ktor-client-okhttp` on Android (wraps OkHttp, keeps disk cache + connection pool config); `ktor-client-darwin` on iOS. Public surface in `WebTelegramClient`, `WebCustomEmojiResolver`, `LocalWebHttpClient` is now Ktor's `HttpClient`. OkHttp lib itself stays as transitive dep + Coil's image fetcher.
  - `androidx.datastore` 1.2.0 → 1.2.1 with `datastore-preferences-core` added to commonMain (KMP variant). Okio 3.10.2 added for KMP path handling. Store-by-store migration + `expect/actual` factory lands together in Phase C.
  - `app.cash.sqldelight:android-driver` split — added `sqldelight-native-driver` for iOS. `.sq` files moved from `androidMain/sqldelight/` to `commonMain/sqldelight/`. `expect class DriverFactory { fun createDriver(): SqlDriver }` in commonMain; `actual` per-platform (Android: PRAGMA WAL/NORMAL/FK on; iOS: NativeSqliteDriver — WAL is iOS default).
- **Pending (deferred — nice-to-have, not blocking iOS guest-mode):**
  - `coil-network-okhttp` → `coil-network-ktor3` (cosmetic, align with Ktor stack).
  - `compose-ui-text-google-fonts` → bundled `.ttf` in `commonMain/composeResources/font/`.
- **Stays Android-only (behind expect/actual or skipped on iOS):** `androidx.media3` (ExoPlayer; iOS uses AVPlayer expect/actual), `coil-gif` / `coil-video`, `core-splashscreen`, `androidx.appcompat`, `androidx.browser`, `leakcanary`, `androidx.profileinstaller`, `androidx.graphics:graphics-shapes` (if not in CMP material3).

## commonMain inventory (current state)

Data layer (`shared/src/commonMain/kotlin/dev/lyo/hortay/`):
- `AppConfig.kt`, `Clock.kt` (`nowMs`, `parseIsoToEpochMs`), `PlatformLog.kt`, `PlatformLocale.kt`, `PlatformDispatchers.kt` — KMP helpers (expect/actual).
- `data/` — 12 pure data files (AuthStage, TimelinePost, PostContent, …) plus `FeedOrder`, `IgnoredChannelsStore`, `ReadCursors`, `StringResolver` (with `ComposeResourcesStringResolver` wrapping CMP `getString` via runBlocking), `PreferencesDataStoreFactory`.
- `data/web/` — full web pipeline: `WebFeedSource`, `WebRepository`, `WebTelegramClient` (consumes `defaultWebHttpClient` expect/actual — OkHttp on Android, Darwin on iOS), `WebPostAdapter`, `TmePageParser`, `WebTextRenderer`, `WebCustomEmojiResolver`, `WebFeedScheduler`, `WebPost`, `SubscriptionsStore`, `GuestModeStore`, `MigrationStore`.
- `data/web/db/` — SQLDelight expect/actual `DriverFactory` + `WebDatabaseProvider` (Android resolves Context via `PlatformContextHolder`, iOS needs nothing).

Resources (`shared/src/commonMain/composeResources/`):
- `values/strings.xml` + `values-uk/strings.xml` (EN + UK).
- `drawable/sym_*.xml` (94 vector drawables).
- Plus duplicated in `shared/src/androidMain/res/` for legacy `R.string.*` paths during transitional builds.

UI:
- `:shared:commonMain/kotlin/dev/lyo/hortay/ui/` — the whole UI tree: every screen, every dialog, the entire PostCard chain. Uses CMP `Res.string.*`. Backend access goes through `HortayBackend` (expect/actual) plus a small set of `Local*` CompositionLocal slots.
- `:shared:androidMain/kotlin/dev/lyo/hortay/ui/` — only platform actuals: `MediaShareActions.android.kt`, `ImageDiskCache.android.kt`, `DataSaver.android.kt`, `AndroidLanguagePicker.kt`. TDLib-bound data files (MigrationCoordinator, WebCustomEmojiBridge, TdClient, PostsRepository, CommentsRepository, …) stay here.
- `:shared:iosMain/kotlin/dev/lyo/hortay/di/InitKoin.kt` — `initKoin()` boots the Koin container with `tdlibStubModule` (no real TDLib on iOS yet). Phase II swaps the stub module for the real TDLib-on-iOS bindings.
- `:shared:iosMain/kotlin/dev/lyo/hortay/MainViewController.kt` — iOS entry point. Calls `initKoin()` once via `lazy`, then `koinInject<X>()` resolves every singleton at the top of `ComposeUIViewController`; inner composables keep their explicit params.

## Migration status

**Phase I.5 complete.** Koin 4.x replaces the hand-wired `AppGraph` / `IosAppGraph` — see `shared/src/commonMain/kotlin/dev/lyo/hortay/di/`. Both graphs deleted. Eager singletons (`tdClient`, `lifecycleBridge`, `autoDownloader`, `webFeedScheduler`, `webCustomEmojiBridge`, `migrationCoordinator`, `logoutCleanup`) flagged `createdAtStart = true`. The historical `runLogoutCleanup` orchestration moved into `LogoutCleanup` (`shared/src/androidMain/kotlin/dev/lyo/hortay/data/LogoutCleanup.kt`), registered + bound eagerly. Entry points (`MainActivity`, `MainViewController`) use `koinInject<X>()`; inner composables keep explicit params. ViewModels via `koinViewModel(parameters = { parametersOf(...) })`.

**Phase I complete.** Final code distribution: **49 androidMain / 176 commonMain / 20 iosMain** (started Phase H at 111 / 87 / 9). iOS guest mode reaches feature parity with Android guest mode: same `WebModeScaffold` tree, same `PostCard`, same `SettingsScreen`, same predictive back, same deep-link nudges.

**Completed phases:**
- Phase 1 — KMP/CMP build system
- Phase A2 — Jsoup → Ksoup
- Phase A3 — OkHttp → Ktor
- Phase A4 — Coil-network-okhttp → coil-network-ktor3
- Phase A6 — DataStore KMP (factory + okio)
- Phase A7 — SQLDelight multiplatform (native-driver + expect/actual DriverFactory)
- Phase B — iOS targets (iosArm64, iosSimulatorArm64, iosX64)
- Phase C — 12 pure data files + resources moved to commonMain
- Phase D — `:iosApp` Xcode project
- Phase E — iOS verification on Mac (build + simulator launch)
- Phase F — Real iOS UI with DataStore persistence
- Phase G — Bulk `R.*` → CMP `Res.*`, web pipeline + Coil-compose to commonMain.
- Phase H — `VideoPlayer` / `MediaCache` / `CustomEmojiRepository` expect/actual; PostCard / PostBody / RichText / sticker / video player chains to commonMain; `HortayBackend` expect/actual born here.
- Phase I — every remaining UI screen moves to commonMain; `HortayBackend` grows the full TDLib-shaped surface (folders, archive, reports, settings, stats, autoDownload, translations, comments, reactions, polls, …); platform abstractions for `LocalMediaShareActions`, `LocalLanguagePicker`, `LocalGuestReportDelegate`, `ClipEntry.plainText`, `DataSaver`, `ImageDiskCache`. iOS `MainViewController` collapses from a 583-line parallel renderer to a 75-line mount of `WebModeScaffold`.

**Deferred:**
- Phase A5 — Google Fonts → bundled fonts (cosmetic).
- **Phase II** — TDLib iOS port via cinterop. Cross-compile `libtdjni` for Apple, write cinterop `.def` files for ~200 `TdApi.*` types, port `TdClient.kt` to cinterop, flip `HortayBackend.ios.actual` from the stub to the real impl. Multi-week.

**Irreducible androidMain (Phase II floor):** `MainActivity` + `HortayApp` + `tdlibModule` + `LogoutCleanup` + ~30 TDLib-bound data files (TdClient, PostsRepository, ChannelActionsRepository, CommentsRepository, MediaCache.android, CustomEmojiRepository (the actual), MessageMapper, MessageContentMapper, AutoDownloadStore, StatsRepository, TelegramLinkResolver, ChatPresence, ChatFoldersRepository, TdLifecycleBridge, TdSender, TdErrorMapping, AuthErrorMessages, TimelineSnapshotStore, TranslationsStore, LocaleStore, MediaAutoDownloader, MigrationCoordinator, WebCustomEmojiBridge, report/*) + platform actuals.
