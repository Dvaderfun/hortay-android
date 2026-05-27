package dev.lyo.hortay.tdlib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Cinterop seam to TDLib's `td_json_client.h`. The only iOS code split
 * per target — everything above (`TypedTdClient`, `IosTdAuthStateMachine`,
 * `HortayBackend`) lives in `iosMain` and consumes this contract.
 *
 * `iosArm64Main` actual: real cinterop pump (Konan calls into the
 * statically-linked `libtdjson.a` from `libtdlight.xcframework`).
 */
expect class TdJsonClient(scope: CoroutineScope) {
    /** Hot stream of raw JSON updates from TDLib. */
    val updates: SharedFlow<String>

    /** Enqueue a JSON-encoded request. Non-blocking. Pair via `@extra` for replies. */
    fun send(json: String)
}
