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
- `:shared` — KMP library with Compose Multiplatform UI + data layer. Plugins: `kotlin.multiplatform` + `com.android.kotlin.multiplatform.library` + `org.jetbrains.compose`. Targets: `android()`, `iosArm64()`. Source sets: `commonMain` (shared), `androidMain` (Android-specific), `iosMain` (iOS-specific actuals + `MainViewController` entry). Naming per [May 2026 JB KMP default structure](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/).
- `:iosApp` — iOS application shell. Xcode project (`iosApp.xcodeproj`). `iOSApp.swift` SwiftUI entry, `ContentView.swift` wraps `MainViewControllerKt.MainViewController()` from the shared framework via `UIViewControllerRepresentable`. Xcode build phase calls `:shared:embedAndSignAppleFrameworkForXcode`. **Mac + Xcode 16+ required to build.**
- `:libtdlib` — TDLib JNI (Android-only; iOS port deferred — see "TDLib iOS strategy" in ARCHITECTURE.md).
- `:baselineprofile` — macrobenchmark.

Code distribution: **~40 files in `androidMain`, ~200 in `commonMain`, ~30 in `iosMain`**. Every UI screen lives in commonMain. Nav substrate: nav3 `NavDisplay` (see `ARCHITECTURE.md`). DI is Koin 4.x — `di/` modules in commonMain (`coreModule`, `storesModule`, `webModule`, `viewModelModule`, `uiBridgeModule`), per-platform `tdlibModule` (androidMain) / `tdlibStubModule` (iosMain), `platformModule()` `expect/actual`. androidMain is the irreducible core: `HortayApp` + `tdlibModule` + TDLib-bound repositories + `LogoutCleanup` + platform actuals (Coil disk cache, status bar, language picker, ConnectivityManager data-saver probe). iOS guest mode mounts the same `WebModeScaffold` tree the Android guest-mode path renders.

## Build commands

```bash
./gradlew :androidApp:installDebug
./gradlew :androidApp:assembleDebug
./gradlew :shared:compileAndroidMain                   # KMP library only
./gradlew :shared:compileKotlinIosArm64                # iOS Kotlin compile (works on Windows!)
./gradlew :shared:linkDebugFrameworkIosArm64           # iOS framework link (Mac only)
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

- **Don't add `com.android.application` to `:shared`.** AGP 9 forbids it alongside KMP plugin. App-shell stays in `:androidApp`.
- **Runtime constants go through `BuildKonfig`.** `generateBuildKonfig` task in `shared/build.gradle.kts` generates `dev.lyo.hortay.BuildKonfig` — accessible from `commonMain`. Debug detection uses `expect/actual isDebugBuild`.
- **Manifest uses FQCN** (`android:name="dev.lyo.hortay.HortayApp"`) — not `.HortayApp`. Namespace-relative resolution would look in wrong package because `:androidApp` namespace is `dev.lyo.hortay.app`.
- **AGP 9 + dual jniLibs `srcDirs` = duplicate-resource error.** `libtdlib/build.gradle.kts` picks unstripped overlay when present, falls back to committed stripped libs otherwise.
- **AndroidUnitTest source set is `androidHostTest`** under `com.android.kotlin.multiplatform.library` plugin (not `androidUnitTest`). Requires `kotlin { android { withHostTest {} } }`.
- **Compose deps in androidApp need Compose BOM.** Library exposes via `implementation`, not `api`, so transitive deps don't reach androidApp. BOM-managed `compose.ui.tooling*` requires the platform reference.
- **CMP `composeCompiler { reportsDestination / metricsDestination }` breaks iOS targets on Windows.** Compose compiler's JsonBuilder writes filenames with colons (target-name-derived), invalid on NTFS. Disable the destinations in `shared/build.gradle.kts` or scope per-target. Stability config (`stabilityConfigurationFiles.add(...)`) still works.
- **iOS Kotlin compile works on Windows** (`compileKotlinIosArm64`). The Apple toolchain is only required for **link** (`.framework` binary) + Xcode bundle. Use this to validate iOS sources cross-platform during development.

## KMP library status

KMP-native stack: Ksoup (HTML), Ktor (HTTP), DataStore (preferences), SQLDelight (web.db), Compottie (Lottie), Koin (DI). Android-only behind expect/actual: ExoPlayer (iOS uses AVPlayer), Coil video/GIF, splashscreen, LeakCanary. Pending cosmetic: `coil-network-ktor3` alignment, bundled fonts.

## Source set overview

**commonMain** (~200 files) — all UI screens, data models, DI modules, web pipeline, SQLDelight `.sq` files. Uses CMP `Res.string.*` / `Res.drawable.*`. Backend access through `HortayBackend` (expect/actual) + `Local*` CompositionLocal slots.

**androidMain** (~40 files) — TDLib-bound repositories (TdClient, PostsRepository, CommentsRepository, etc.), platform actuals (MediaShareActions, ImageDiskCache, DataSaver, VideoPlayer), `LogoutCleanup`. `androidMain/res/` has only launcher icons, themes, and Android XML config — no duplicate drawables or strings.

**iosMain** (~30 files) — `MainViewController.kt` (iOS entry), `InitKoin.kt` (boots Koin with `tdlibStubModule` or real TDLib module), platform actuals, TDLib cinterop bindings (`TdJsonClient`, `TypedTdClient`).

**Resources** (`commonMain/composeResources/`): `values/strings.xml` (EN) + 12 locale dirs (uk, ar, de, es, fa, fr, id, it, pl, pt-BR, ru, tr). 96 `sym_*.xml` vector drawables.

## Migration status

KMP/CMP migration (Phases 1 through I.5) is **complete**. All UI, data models, resources, DI, and nav live in commonMain. Koin 4.x replaced the hand-wired `AppGraph`. Nav3 replaced the bespoke `NavOverlayRenderer`. iOS guest mode has feature parity with Android guest mode.

**Deferred:**
- Bundled fonts (cosmetic — Google Fonts still used).
- **Phase II** — TDLib iOS authenticated mode. cinterop `.def` files + sealed-class TdApi foundation landed; remaining: cross-compile `libtdjni` for Apple, port remaining TDLib-bound repos, flip `HortayBackend.ios.actual` from stub to real. Multi-week.

**Irreducible androidMain** (~40 files, floor for Phase II): `tdlibModule` + TDLib-bound repositories (TdClient, PostsRepository, CommentsRepository, ChannelActionsRepository, MediaCache, CustomEmojiRepository, MessageMapper, StatsRepository, ChatPresence, etc.) + `LogoutCleanup` + platform actuals.
