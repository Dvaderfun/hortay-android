package dev.lyo.hortay.tdlib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Cinterop seam to TDLib's `td_json_client.h`. The only iOS code we have to
 * split per target — everything above (`TypedTdClient`,
 * `IosTdAuthStateMachine`, `HortayBackend`) lives in `iosMain` and consumes
 * this contract.
 *
 *   - `iosArm64Main` actual: real cinterop pump (Konan calls into the
 *     statically-linked `libtdjson.a` from `libtdlight.xcframework`).
 *   - `iosSimulatorArm64Main` actual: stub. `libtdlight.xcframework` ships
 *     only the ios-arm64 slice (Phase II-A scope), so the simulator path
 *     can't link `td_send`/`td_receive`. The stub returns a single synthetic
 *     `updateAuthorizationState{ authorizationStateWaitPhoneNumber }` on
 *     startup so the routing in `MainViewController` lands on `AuthScreen`
 *     and the user can tap "Continue without account" to enter guest mode.
 *
 * Phase II-A pinned this asymmetry: a working simulator slice would require
 * cross-building TDLib for `ios-arm64-simulator` + `ios-x86_64-simulator`,
 * doubling the per-bump build time for zero new use case.
 */
expect class TdJsonClient(scope: CoroutineScope) {
    /** Hot stream of raw JSON updates from TDLib. */
    val updates: SharedFlow<String>

    /** Enqueue a JSON-encoded request. Non-blocking. Pair via `@extra` for replies. */
    fun send(json: String)
}
