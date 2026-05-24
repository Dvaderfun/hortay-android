# Phase II — TDLib on iOS, authenticated mode

End state of Phase I: iOS guest mode reaches feature parity with Android guest mode
(same `WebModeScaffold` tree, same `PostCard`, same predictive back, same deep-link
nudges). The stub `HortayBackend.ios.kt` short-circuits every TDLib-bound call.

**Phase II target:** flip `HortayBackend.ios.kt` from stub to a real TDLib-backed
implementation so iOS users can sign in with their Telegram account and read
their full subscribed feed, same as Android. After Phase II the iOS app and the
Android app share **every** runtime behavior except the platform actuals for
ExoPlayer (still Android-only; iOS keeps AVPlayer via the Phase H expect/actual)
and a handful of OS-system actuals.

Multi-week project. Roughly 6 sequential sub-phases.

---

## North star

> The iOS Hortay user signs in with phone number → SMS code, lands on the
> authenticated feed, scrolls their subscribed channels, taps into comments,
> reacts to a post, hides a channel, reports a post — every gesture goes
> through the same `HortayBackend` interface the Android app uses, but the
> iOS `actual class` talks to a TDLib instance compiled native for arm64-ios.

Concretely:

- iOS Simulator + iPhone device builds run on Xcode 16+.
- `HortayBackend.ios.kt` is no longer the stub — every method routes through
  a Kotlin/Native `TdClient.kt` that wraps TDLib's `tdjson` C interface via
  cinterop.
- TDLib's `td_api.tl` schema is the single source of truth for both Android
  (existing Java `TdApi`) and iOS (new generated `TdApiNative.kt`). The
  generator script regenerates both on each `update-tdlib.sh` run.
- The Phase I commonMain UI tree compiles unchanged. Every screen already
  takes `backend: HortayBackend`; only the iOS actual switches over.

---

## Execution plan

### II-A — Native TDLib build for Apple platforms (~3-5 days, Mac required)

Cross-compile TDLib for three slices and bundle into a single
`libtdlight.xcframework`:

| Slice | Triple | Why |
|---|---|---|
| `ios-arm64` | `arm64-apple-ios` | iPhone device (App Store distribution) |
| `ios-arm64-simulator` | `arm64-apple-ios-simulator` | Apple Silicon Mac simulator |
| `ios-x86_64-simulator` | `x86_64-apple-ios-simulator` | Intel Mac simulator (legacy, keep for CI) |

Build script lives at `scripts/tdlib-builder/build-tdlib-apple.sh`. Mirrors the
existing Android `build-tdlib-parallel.sh` shape:

1. Clone TDLib at the SHA pinned in `scripts/tdlib-version.txt` (same pin as
   Android — keeps the protocol version in lockstep).
2. Cross-compile OpenSSL for each slice (TDLib needs static OpenSSL ≥ 3.x).
3. Run TDLib's CMake with `CMAKE_TOOLCHAIN_FILE` pointing at Apple's
   `ios.toolchain.cmake` (vendored from `leetal/ios-cmake` — battle-tested,
   used by tdlight-java and tdlib-rs).
4. Build target = `tdjson_static` (NOT `tdjni` — iOS uses the JSON interface,
   not the Java JNI bridge).
5. `xcodebuild -create-xcframework` zips the three slices into
   `libtdlib/build/apple/libtdlight.xcframework`.

Output:
```
libtdlib/build/apple/libtdlight.xcframework/
  ios-arm64/libtdjson.a
  ios-arm64-simulator/libtdjson.a
  ios-x86_64-simulator/libtdjson.a
  Headers/td/telegram/td_json_client.h
  Info.plist
```

**Verification gate:**
- `lipo -info libtdjson.a` returns the expected arch for each slice.
- `nm -gj libtdjson.a | grep td_create_client_id` resolves the exported
  symbols.

**Why Mac required:** Apple's toolchain only links against iOS SDKs hosted in
Xcode.app. The existing Docker-on-Linux Android build cannot produce iOS
binaries.

### II-B — Cinterop binding for `tdjson` (~1 day)

`shared/src/iosMain/cinterop/tdjson.def`:

```
package = dev.lyo.hortay.tdlib.native
headers = td/telegram/td_json_client.h
staticLibraries = libtdjson.a
libraryPaths.ios_arm64 = libtdlib/build/apple/libtdlight.xcframework/ios-arm64
libraryPaths.ios_arm64_simulator = libtdlib/build/apple/libtdlight.xcframework/ios-arm64-simulator
libraryPaths.ios_x86_64_simulator = libtdlib/build/apple/libtdlight.xcframework/ios-x86_64-simulator
linkerOpts = -framework Foundation -lc++ -lssl -lcrypto
```

`shared/build.gradle.kts` declares the cinterop for each iOS target:

```kotlin
kotlin {
    iosArm64 { compilations.getByName("main").cinterops { create("tdjson") } }
    iosSimulatorArm64 { compilations.getByName("main").cinterops { create("tdjson") } }
}
```

After this lands, `import dev.lyo.hortay.tdlib.native.td_create_client_id` /
`td_send` / `td_receive` / `td_execute` works from any iosMain file.

**Verification gate:** `:shared:compileKotlinIosSimulatorArm64` resolves the
five `td_*` function signatures with zero unresolved references.

### II-C — `TdApiNative` code generation (~3-5 days)

TDLib's authoritative schema is `td/generate/scheme/td_api.tl` — about
~2 000 type declarations and ~600 RPC methods. Hand-writing every Kotlin
data class is a non-starter; we generate them.

Two options:

**Option A — clone TDLib's existing generators.** TDLib ships `td-api.json`
(generated from `td_api.tl`) and ~5 reference language generators
(`td/generate/JavadocTlDocumentationGenerator.cpp` for Java,
`td/generate/TlWriterPython.cpp` for Python, etc.). Add a
`TlWriterKotlinMultiplatform.cpp` that emits commonMain `data class` /
`sealed class` definitions tagged with `@kotlinx.serialization.SerialName(...)`.

**Option B — JSON-driven Kotlin generator.** Take TDLib's already-published
`td_api.json` (one line in the build script: `td_api_json --output td_api.json`)
and write a small Kotlin script (`scripts/tdapi-gen/main.kt`) that emits
`shared/src/commonMain/kotlin/dev/lyo/hortay/tdlib/TdApiNative.kt`.

**Recommended: Option B.** The C++ template route is faster for upstream
TDLib maintainers (Aliaksei Levin already wrote half of the existing
writers) but slower for us — we'd have to upstream a patch every TDLib bump.
A Kotlin generator we control bumps with us.

Output: a single ~30 000-line `TdApiNative.kt` file under
`shared/src/commonMain/kotlin/dev/lyo/hortay/tdlib/`. Same package, same
class names as the existing Java `org.drinkless.tdlib.TdApi.*` — so the
existing Android `TdClient.kt` can keep using its current types.

Wait — that won't work directly because the existing Android `TdApi` is in
package `org.drinkless.tdlib` (TDLib upstream FQCN), and the iOS one lives
in `dev.lyo.hortay.tdlib`. The migration ladder:

1. Generate `TdApiNative.kt` in `dev.lyo.hortay.tdlib` (iosMain) for now.
2. Phase II-D below maps the iOS-side `TdApiNative.*` calls to TDLib JSON
   inside the iOS `TdClient.kt`.
3. The commonMain `HortayBackend` interface stays TdApi-free — it only ever
   sees the Phase-I-defined DTOs (TimelinePost, FormattedText, etc.). The
   per-platform `actual class` is the only place that touches TdApi.

This is the same shape Android already uses: `HortayBackend.android.kt`
talks to `org.drinkless.tdlib.TdApi.*` internally and exposes only commonMain
DTOs to the UI.

**Verification gate:** `:shared:compileKotlinIosSimulatorArm64` compiles the
generated file (~30s on Apple Silicon). `nm` confirms no symbol bloat in
the final binary because k/n LTO drops unused classes.

### II-D — Write the iOS `TdClient.kt` (~5-7 days)

`shared/src/iosMain/kotlin/dev/lyo/hortay/data/TdClient.ios.kt`. Mirrors the
Android `TdClient.kt` (784 lines) shape:

- One client id from `td_create_client_id()`.
- One long-lived coroutine pumping `td_receive(timeout)` on
  `Dispatchers.IO`. Each received string parses via kotlinx.serialization
  into a `TdApiNative.*` `Update` subtype, emits into the same shape of
  `UNLIMITED Channel → SharedFlow(64)` Android uses.
- `send(query)` serializes the request, drops `@extra` for tagging,
  `td_send(clientId, json)`. The receive pump correlates `@extra` to
  resume the awaiting suspending function.
- `authStage` / `authError` / `connection` / `floodWaitUntilMs` flows are
  identical to Android — same `MutableStateFlow` wiring.
- `FLOOD_WAIT` global gate (the `AtomicLong` deadline) — already commonMain
  via `kotlinx.atomicfu.atomic`, lift it out of `TdClient` to commonMain
  if it isn't already so Android + iOS share one implementation.

Then re-implement every method of `HortayBackend.ios.kt` actual — currently
~50 no-ops returning `null` / empty. Each routes to a `TdApiNative.*`
request through the iOS `TdClient`. The mapping is mechanical because
the surface is already defined in the commonMain `HortayBackend` expect.

Auth / countries:
- `loadCountries()` → `TdApiNative.GetCountries()`
- `submitPhone(e164)` → `SetAuthenticationPhoneNumber(...)`
- `submitCode(code)` → `CheckAuthenticationCode(...)`
- `submitPassword(p)` → `CheckAuthenticationPassword(...)`
- … (same as Android one-liners)

Feed orchestration: the big one. The iOS `TdClient` needs to host the
same `PostsRepository` logic — UNLIMITED Channel update fan-out, lifecycle
gates, chatCache, FLOOD_WAIT-aware throttling, snapshot restore. The
Android `PostsRepository.kt` is 2 000+ lines; porting it to iOS is the
bulk of the Phase II work.

**Strategy:** lift `PostsRepository.kt` into commonMain with an `expect`
`TdSender` interface (its only platform dependency is `TdApi.*` types).
Both platforms supply the same `PostsRepository` impl; only the
platform-specific `TdApi` typedefs differ.

This deserves its own sub-plan — call it **II-D1: lift PostsRepository to
commonMain** — because the porting work is identical in shape to the
existing Phase H "lift this android class to commonMain" pattern.
Probably 2 weeks on its own.

**Verification gate:** end-to-end run on iPhone 15 simulator:
1. Mount the app, see AuthScreen.
2. Enter a real Telegram phone number, receive SMS, type code.
3. Land on the authenticated feed.
4. See exactly the same channels / posts the Android app shows for the
   same account.
5. Tap a post → comments overlay renders the same thread.
6. React to a post → the official Telegram client (a separate phone) shows
   the same reaction.

### II-E — iOS sign-in flow polish (~2 days)

- Push notifications: iOS uses APNS, not FCM. TDLib's
  `RegisterDevice(DeviceTokenApplePush)` handles this; we need to plumb
  the APNS token from the SwiftUI host into `IosAppGraph`.
- Universal links: `tg://` schemes don't work the same way on iOS.
  Wire `Universal Links` in `iosApp/iosApp.entitlements`,
  `apple-app-site-association` hosted at `t.me/`, and handle the
  `NSUserActivity` callback in `iOSApp.swift` → forward to
  `IosAppGraph.deepLinkRouter`.
- Background fetch: TDLib's `SetOption("online", false)` + `SetNetworkType`
  match Android's `TdLifecycleBridge`. iOS only — the bridge needs an
  iosMain actual that observes `UIApplication.didEnterBackgroundNotification`.

### II-F — Production polish (~3 days)

- App Store submission: iOS in-app reporting must use the same TDLib
  `ReportChat` flow Android does — already wired through the commonMain
  `ReportFlowController`. The iOS actual flips from the no-op stub to the
  real `ReportRepository` (which itself goes commonMain alongside
  PostsRepository).
- Build pipeline: GitHub Actions Mac runner. `xcodebuild archive` +
  notarization + TestFlight upload. Mirror the existing Android beta CI.
- TDLib bumps: every `./scripts/update-tdlib.sh` run now also bumps the
  iOS xcframework. Script needs to invoke both `build-tdlib-parallel.sh`
  (Android, Docker) and `build-tdlib-apple.sh` (Mac, xcodebuild).
  Skippable in pure-Android-bump scenarios.
- CHANGELOG: first user-visible Phase II bullet — "iOS app launches into
  the App Store with full Telegram sign-in and read parity with Android."

---

## What we deliberately punt to Phase III

- **Wear OS.** Decide before any module-split that would either help or
  hurt it.
- **WebAssembly / Desktop targets.** Almost free after Phase II
  (everything in commonMain already targets iosMain + androidMain; Wasm
  needs only a `wasmJsMain` source set + JS Ktor client and a Web-mode
  `WebDatabaseProvider`).
- **Compottie WebP animation.** Verify whether Compottie supports
  animated WebP; if so, migrate WebM custom emojis from the static-thumb
  fallback to Compottie's poster path on iOS.
- **TDLib's `tdjson_callback`-style API.** The default `td_receive`
  poll-loop is fine for now; switching to the callback variant saves one
  thread but needs careful thread-safety review.

---

## Critical decisions ahead of execution

1. **JSON vs raw C++ FFI.** Choose `tdjson` (JSON-based, language-agnostic)
   over `tdjni` (Java-only) for the iOS surface. JSON parses through
   kotlinx.serialization — fast enough, and we avoid hand-writing 600 cinterop
   binding signatures for each `TdApi.*` method.

2. **TLD generator: Option B (Kotlin script driving `td_api.json`).**
   Versus Option A (upstream TDLib C++ writer). We control our generator;
   bumping TDLib doesn't require an upstream PR.

3. **PostsRepository goes commonMain in Phase II-D1.** With a small
   `expect interface TdSender { suspend fun send(query: TdApi.Function): TdApi.Object }`
   the same code drives both platforms. Cuts the iOS port in half.

4. **OpenSSL static-link, not dynamic.** TDLib needs ≥ 3.x; iOS system
   OpenSSL is too old. Static link adds ~3 MB per arch — acceptable.

5. **Don't try to delete `org.drinkless.tdlib` on Android.** The JNI symbol
   lookup in `libtdjni.so` depends on the FQCN. Even though iOS uses a
   different package, Android keeps its existing path.

6. **`HortayBackend` stays the contract.** Phase II adds **zero** new
   methods to it. Every commonMain UI screen continues to compile
   unchanged; only the iOS `actual` changes.

---

## Verification gates (rolling)

- After II-A: `lipo -info` shows three slices.
- After II-B: `:shared:compileKotlinIosSimulatorArm64` resolves
  `td_create_client_id` / `td_send` / `td_receive`.
- After II-C: `:shared:compileKotlinIosSimulatorArm64` compiles the
  ~30 000-line generated file.
- After II-D: iOS Simulator signs in, the feed populates with the same
  post set the Android app shows for the same account.
- After II-E: deep links from a browser open into the iOS app's
  channel screen.
- After II-F: TestFlight build accepts the binary; App Store reviewers
  pass the CSAE compliance audit.

---

## Reference projects

- **TDLib `td_json_client.h`** — <https://github.com/tdlib/td/blob/master/td/telegram/td_json_client.h>.
  5 functions, JSON in / JSON out. Stable since 2017.
- **leetal/ios-cmake** — CMake toolchain file for Apple platforms. Used by
  TDLib's official iOS build scripts.
- **tdlight-java** — TDLib's Java bindings with a JSON path as a fallback.
  Worth skimming their `TdApi` generator.
- **Drinkless/tdlib-rs** — Rust bindings using `tdjson`. The cleanest
  reference for a non-JVM language using TDLib's JSON interface.
- **Aliaksei Levin's `tdlib/td` issues** — search for "iOS" / "swift" —
  the maintainer answers cross-platform questions directly.

---

## Out of scope, but worth flagging

- **Wear OS / desktop / wasm parity.** Wait until Phase II ships before
  even prototyping.
- **TDLib version bumps mid-Phase-II.** Pin to the same SHA the Android
  app uses for the duration; bumping mid-port risks an iOS-only
  regression that's hard to diagnose.
- **TestFlight beta cohort.** Start small (10 users) — the iOS app has
  never been on a real device under load.
