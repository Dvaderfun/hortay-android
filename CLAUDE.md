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

Current code distribution: ALL ~177 Kotlin source files in `shared/src/androidMain/kotlin/`. `commonMain` exists but empty. Phases 2-5 of migration move files into `commonMain` + add expect/actual abstractions for platform services.

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
  - `okhttp` direct usage → `io.ktor:ktor-client-*` 3.5.0. Engines: `ktor-client-okhttp` on Android (wraps OkHttp, keeps disk cache + connection pool config); `ktor-client-darwin` on iOS. Public surface in `WebTelegramClient`, `WebCustomEmojiResolver`, `LottieUrlStore`, `LocalWebHttpClient` is now Ktor's `HttpClient`. OkHttp lib itself stays as transitive dep + Coil's image fetcher.
  - `androidx.datastore` 1.2.0 → 1.2.1 with `datastore-preferences-core` added to commonMain (KMP variant). Okio 3.10.2 added for KMP path handling. Store-by-store migration + `expect/actual` factory lands together in Phase C.
  - `app.cash.sqldelight:android-driver` split — added `sqldelight-native-driver` for iOS. `.sq` files moved from `androidMain/sqldelight/` to `commonMain/sqldelight/`. `expect class DriverFactory { fun createDriver(): SqlDriver }` in commonMain; `actual` per-platform (Android: PRAGMA WAL/NORMAL/FK on; iOS: NativeSqliteDriver — WAL is iOS default).
- **Pending (deferred — nice-to-have, not blocking iOS guest-mode):**
  - `lottie-compose` → `io.github.alexzhirkevich:compottie` (custom emoji TGS playback).
  - `coil-network-okhttp` → `coil-network-ktor3` (cosmetic, align with Ktor stack).
  - `compose-ui-text-google-fonts` → bundled `.ttf` in `commonMain/composeResources/font/`.
- **Stays Android-only (behind expect/actual or skipped on iOS):** `androidx.media3` (ExoPlayer; iOS uses AVPlayer expect/actual), `coil-gif` / `coil-video`, `core-splashscreen`, `androidx.appcompat`, `androidx.browser`, `leakcanary`, `androidx.profileinstaller`, `androidx.graphics:graphics-shapes` (if not in CMP material3).

## commonMain inventory (post-Phase C partial)

Data layer files moved to `shared/src/commonMain/kotlin/dev/lyo/hortay/data/`:
- `AuthStage.kt`, `ConnectionStatus.kt`, `FormattedText.kt`, `PostContent.kt`, `TimelinePost.kt`, `UserMessageBus.kt`, `PostFilterStrategy.kt`, `ReactionTogglePolicy.kt`, `ThreadRow.kt`, `FeedSource.kt`, `DeepLinkRouter.kt`
- `report/ReportDialogState.kt`
- `web/db/DriverFactory.kt` (`expect class`)

Stays in androidMain (transitively depend on types still Android-resident):
- `LinkDialogState.kt` (depends on `ChatInvitePreview` declared in `ChannelActionsRepository.kt`).
- `ReadCursors.kt` (depends on `FeedOrder` enum declared in `SettingsStore.kt`).
- All UI files (use `R.string.*` — need composeResources migration first).

Next moves (Phase C continuation, future session):
1. Extract `FeedOrder` + `ChatInvitePreview` into standalone files in commonMain → unblock LinkDialogState + ReadCursors moves.
2. CMP composeResources migration (`R.string.*` → `Res.string.*`) → unblocks ~48 UI files.
3. expect/actual `PlatformContext` for `LocalContext.current` usages → unblocks any UI file using Android Context directly.
