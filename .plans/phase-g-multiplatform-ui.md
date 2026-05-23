# Hortay KMP/CMP Migration — Phase G: Finish Multiplatform UI

## Context

Phases 1–F brought the project to a working multiplatform shell. Both `./gradlew :androidApp:assembleDebug` and `xcodebuild -scheme iosApp` succeed. iOS Simulator runs a real Compose Multiplatform UI with persistent DataStore. **But:** Hortay's full UX still lives in `shared/src/androidMain/kotlin/` (165 files). iOS only sees a basic subscriptions screen.

The next session finishes the migration so iOS Simulator runs the SAME UX as Android, not a minimal stand-in.

## Current state (commit `56ce6a5`)

| Layer | State |
|-------|-------|
| Build system | KMP/CMP with `:androidApp` + `:shared` + `:libtdlib` + `:iosApp` + `:baselineprofile`. AGP 9.2 + CMP 1.12.0-alpha01 + Kotlin 2.3.10. |
| iOS targets | `iosArm64`, `iosSimulatorArm64`, `iosX64`. Framework links on Mac, app runs in Simulator. |
| Resources | `strings.xml` + 94 drawables in `shared/src/commonMain/composeResources/`. Also duplicated in `shared/src/androidMain/res/` so existing `R.string.*` still resolves on Android. |
| KMP libs swapped | Ksoup (was Jsoup), Ktor (was OkHttp), Compottie (LottieStickerView only), Coil-ktor3 (was coil-network-okhttp), SQLDelight native-driver, DataStore KMP (factory + okio path). |
| Already in `commonMain` | 12 pure data files (AuthStage, TimelinePost, PostContent, …) + SubscriptionsStore + DriverFactory expect/actual. |
| iOS UI | `MainViewController.kt` mounts a working but minimal subscriptions screen. Uses real DataStore via KMP factory. |

## What still lives in `androidMain` (the 165 files)

```
60 files use R.string / R.drawable / R.plurals    ← bulk-convertible
37 of those use ONLY resources (no other Android-specific)  ← clean move
25 files use org.drinkless.tdlib (TDLib)          ← need expect/actual layer
24 files use android.content.Context              ← case-by-case
 5 files use androidx.media3 (ExoPlayer)          ← expect VideoPlayer
 6 files use com.airbnb.lottie (Lottie inline)    ← inline-emoji animator path
```

## Phase G plan

### G1 — Bulk R.string → Res.string migration script (HIGHEST LEVERAGE)

Goal: unblock 37 UI files at once with one mechanical pass.

Per-file edits:

```kotlin
// BEFORE
import androidx.compose.ui.res.stringResource
import dev.lyo.hortay.R
...
Text(stringResource(R.string.nav_feed))

// AFTER
import org.jetbrains.compose.resources.stringResource
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.nav_feed
...
Text(stringResource(Res.string.nav_feed))
```

Same pattern for `painterResource(R.drawable.foo)`, `pluralStringResource(R.plurals.foo, …)`.

**Tricky cases:**
- `@StringRes val labelRes: Int = R.string.foo` → `val labelRes: StringResource = Res.string.foo`. Type changes — callers don't need updates if they use `stringResource(labelRes)`.
- `stringResource(R.string.foo, arg1, arg2)` → `stringResource(Res.string.foo, arg1, arg2)` (CMP signature matches).
- Per-string imports are tedious. Wildcard `import hortay.shared.generated.resources.*` works for new code; old `R.string.foo_bar` calls each need explicit `import hortay.shared.generated.resources.foo_bar`.

**Recommended script:** write a Python/awk script that walks each file:
1. Extracts every `R.string.foo_bar` / `R.drawable.foo` / `R.plurals.foo` reference.
2. Removes `import dev.lyo.hortay.R` and `import androidx.compose.ui.res.stringResource` (and `painterResource`, `pluralStringResource`).
3. Adds `import hortay.shared.generated.resources.Res` + per-symbol imports.
4. Adds `import org.jetbrains.compose.resources.stringResource` (etc).
5. Rewrites `R.string.foo` → `Res.string.foo` (and drawable, plurals).

Run script over the 37 candidate files. Then `git mv` each to `shared/src/commonMain/kotlin/...`. Build, fix any cascading import issues.

**Identifying the 37 candidates** (already done in current session):
```bash
grep -rl "R\.string\|R\.drawable\|R\.plurals" shared/src/androidMain/kotlin/dev/lyo/hortay/ui | \
  while read f; do
    if ! grep -qE "import android\.|org\.drinkless|androidx\.media3|com\.airbnb\.lottie" "$f"; then
      echo "$f"
    fi
  done
```

After G1: ~37 UI files now in commonMain. iOS gets real Hortay UI components.

### G2 — TDLib expect/actual abstraction (25 files unblock)

Goal: move TDLib-touching repositories to commonMain. iOS gets stub implementations that throw or return AuthStage.Stub.

Approach:
1. Define `expect interface TdSender` in commonMain with the minimal surface used by repositories (`send(query): Object`, `updates: SharedFlow<Update>`, `loggedOut: SharedFlow<Unit>`).
2. The problem: `query` and `Object` are TDLib Java types (`org.drinkless.tdlib.TdApi.*`). These can't exist in commonMain — they're Java/JNI classes.
3. **Two viable approaches:**

   **(a) Domain types in commonMain:** define Hortay's own `TdRequest` / `TdResponse` sealed hierarchies in commonMain. Android actual translates to/from `TdApi.*` types. Pure abstraction.
   - PRO: Clean. iOS gets meaningful stubs.
   - CON: Massive translation layer (~200 TdApi types used). Touches all repositories. Multi-day work.

   **(b) Keep TDLib repositories in androidMain, expose only their `Flow<DomainType>` outputs through a commonMain interface:** the repositories themselves stay androidMain (have direct `TdApi.*` references), but the data they emit (`TimelinePost`, `PostContent`, `AuthStage`) is already in commonMain. UI in commonMain consumes the flows from a commonMain interface.
   - PRO: Pragmatic. iOS provides stub repositories that emit empty flows.
   - CON: iOS guest-mode-only — no TDLib auth on iOS.

   **Recommend (b)** for Phase G. Full TDLib iOS port via cinterop is a separate track.

4. Implementation of (b):
   - Define `expect class HortayBackend` (or similar) in commonMain with high-level methods: `subscribeToChannel(username)`, `feed: StateFlow<List<TimelinePost>>`, `authStage: StateFlow<AuthStage>`, etc.
   - Android actual delegates to existing `AppGraph` (PostsRepository, TdClient, etc.).
   - iOS actual delegates to `WebFeedSource` + stubs for auth (always returns `AuthStage.Stub` so UI routes to WebModeScaffold).

### G3 — ExoPlayer expect/actual (5 files)

Define `expect interface VideoPlayer` in commonMain. Android actual = ExoPlayer wrapper. iOS actual = AVPlayer (or stub showing static thumb for v1).

Surface needed: `play`, `pause`, `seekTo`, `isPlaying: StateFlow<Boolean>`, `currentPositionMs: StateFlow<Long>`.

### G4 — Lottie inline-emoji animator (6 files)

`CustomEmojiAnimator` + `InlineCustomEmojiRenderer` use Airbnb Lottie's `LottieDrawable.draw(canvas)` for bitmap-bg-rasterisation. Compottie has no `LottieDrawable` equivalent.

**Pragmatic path:** keep this Android-only. iOS shows static thumbs for inline emoji. Acceptable for v1.

**Future improvement:** replace `CustomEmojiAnimator` with a per-composable Compottie renderer (no shared bitmap, just shared composition). Loses performance optimization but works on iOS. Benchmark before committing.

### G5 — Context-tied stores + StringResolver (24 files)

Most `android.content.Context` usages are:
- DataStore (already abstracted via `PreferencesDataStoreFactory.android.kt`)
- StringResolver (Context.resources lookup)
- File paths (filesDir, cacheDir)
- System services (ConnectivityManager, ProcessLifecycleOwner)

Plan:
- **StringResolver:** define `expect class StringResolver { suspend fun get(StringResource): String }`. Both platforms use CMP `getString(Res.string.foo)` under the hood. NOTE: this becomes suspend — repositories that synchronously format strings need refactoring (return a `StringResource` instead of a `String`, let UI resolve).
- **File paths:** define `expect fun appFilesDir(): okio.Path` and `expect fun appCacheDir(): okio.Path`. Android = `context.filesDir`. iOS = `NSDocumentDirectory`.
- **Connectivity:** define `expect class PlatformConnectivity { val isOnline: StateFlow<Boolean> }`. Android wraps ConnectivityManager. iOS wraps NWPathMonitor.
- **Lifecycle:** AndroidX Lifecycle 2.9+ is KMP — use `androidx.lifecycle:lifecycle-runtime-compose` accessors directly. Should "just work" once added to commonMain deps.

### G6 — Move web-mode pipeline to commonMain

WebFeedSource + WebRepository + WebPostAdapter + TmePageParser + WebCustomEmojiResolver + WebTelegramClient are all already KMP-library-only (Ksoup + Ktor + SQLDelight + DataStore). Move them to commonMain after G5 unblocks their Context dependencies (mostly file paths + StringResolver).

### G7 — IosAppGraph + wire MainViewController to WebModeScaffold

After G1-G6, `WebModeScaffold` exists in commonMain. Build `IosAppGraph` (slim version of AppGraph — only web pipeline, no TDLib/ExoPlayer). Wire it from `MainViewController`:

```kotlin
fun MainViewController(): UIViewController = ComposeUIViewController {
    val graph = remember { IosAppGraph() }
    HortayTheme {
        WebModeScaffold(graph = graph)
    }
}
```

## Execution order (next session)

1. **G1 — Bulk R.string migration** (script + run + verify Android still builds). Unlocks 37 files. ~2h.
2. **G5 — Context expect/actual** (StringResolver, file paths, connectivity). ~3h.
3. **G6 — Move web pipeline to commonMain** (gravy after G5). ~1h.
4. **G2 — TDLib HortayBackend interface (option b)** (commonMain interface, androidMain delegates to AppGraph, iosMain stubs). ~2h.
5. **G3 — VideoPlayer expect/actual** (Android = ExoPlayer wrapper, iOS = stub static thumb). ~1h.
6. **G7 — IosAppGraph + WebModeScaffold mount** (final wiring). ~1h.

**Total: ~10 focused hours.** Realistically 2 full sessions with verification + cleanup.

## Critical files to be modified (representative paths)

- All 37 candidate UI files under `shared/src/androidMain/kotlin/dev/lyo/hortay/ui/` (move to `commonMain` after import rewrite).
- `shared/src/androidMain/kotlin/dev/lyo/hortay/data/StringResolver.kt` → split expect/actual.
- `shared/src/androidMain/kotlin/dev/lyo/hortay/AppGraph.kt` → split. Web-mode parts move to commonMain (`SharedAppGraph` or `IosAppGraph`), TDLib parts stay androidMain.
- `shared/src/iosMain/kotlin/dev/lyo/hortay/MainViewController.kt` → replace placeholder with full WebModeScaffold mount.
- New: `shared/src/commonMain/kotlin/dev/lyo/hortay/data/HortayBackend.kt` (expect interface).
- New: `shared/src/iosMain/kotlin/dev/lyo/hortay/data/HortayBackend.ios.kt` (web-only stub).

## Verification after each phase

- `./gradlew :androidApp:assembleDebug` — Android must keep building after every change.
- `./gradlew :shared:compileKotlinIosSimulatorArm64` — iOS Kotlin must compile.
- On Mac: `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug -destination "generic/platform=iOS Simulator" build` — iOS app must build.
- On Mac Simulator: launch + verify UI renders without crash.

## Open questions

- **TDLib iOS port (option a) — defer to Phase H?** Would let iOS support full Telegram auth. Multi-week effort (cross-compile TDLib for iOS via Docker, write cinterop `.def` files for ~200 `TdApi.*` types, port `TdClient.kt`). Recommend deferring — Phase G ships guest-mode parity, which already covers the main UX.
- **Material 3 Expressive on CMP** — does CMP 1.12.0-alpha01's bundled material3 have all Expressive APIs Hortay uses (`MaterialShapes`, `LoadingIndicator`, `FloatingToolbar`, `ButtonGroup`, `FlexibleTopAppBar`, `MotionScheme.expressive()`)? Verify at start of next session — if gaps, may need shims on iOS or version pin.
- **Compottie + skiko mismatch warning** — log shows Compottie compiled against different skiko version than CMP 1.12.0-alpha01. May break gradient TGS stickers at runtime. Test in Simulator. Fix: pin matching Compottie version or wait for upstream catch-up.
