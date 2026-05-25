@file:OptIn(ExperimentalForeignApi::class)

package dev.lyo.hortay.tdlib

import dev.lyo.hortay.tdlib.native.td_create_client_id
import dev.lyo.hortay.tdlib.native.td_execute
import dev.lyo.hortay.tdlib.native.td_receive
import dev.lyo.hortay.tdlib.native.td_send
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin Kotlin wrapper around TDLib's `td_json_client.h` C interface, accessed
 * via the iosArm64 cinterop binding declared in `tdjson.def`.
 *
 * This is **Phase II-D scaffold** — it speaks raw JSON (kotlinx.serialization
 * not wired yet) and exists to prove the cinterop pipeline works end-to-end:
 * the Konan compiler picks up the `.def`, links against `libtdjson.a`, and
 * the resulting Kotlin/Native binary can call into the static TDLib archive.
 *
 * Phase II-C will replace the [String] queries / updates with kotlinx.serialization-
 * encoded `TdApi.*` types. The dispatching shape (one client id, one receive
 * pump, request/response correlation via `@extra`) is identical to the
 * Android `TdClient.kt` — Phase II-D1 lifts the orchestration logic into
 * commonMain so both platforms share the same repository code.
 *
 * Thread-safety: TDLib's tdjson is callable from any thread, but each client
 * id has a single receiver. We pump on Dispatchers.IO and fan updates out via
 * a SharedFlow that downstream collectors observe.
 */
class TdJsonClient(scope: CoroutineScope) {

    /** Stable TDLib-assigned client id. Reused for every td_send/td_receive call. */
    val clientId: Int = td_create_client_id()

    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Hot stream of raw JSON updates from TDLib (broadcast). Collectors get
     * every update emitted after subscription; subscribe before the first
     * authentication query lands or use [updatesReplay] for at-most-N replay.
     */
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    init {
        // Sender pump — funnels outbound queries to td_send. Single-coroutine
        // serialisation isn't required by tdjson (the C API is reentrant) but
        // mirrors the Android shape and lets us add per-query bookkeeping
        // (timing, @extra correlation) in one place in Phase II-D.
        //
        // K/N note: Dispatchers.IO doesn't exist on native — Dispatchers.Default
        // is the equivalent. K/N's default dispatcher backs onto a worker pool
        // similar to JVM's IO dispatcher; the I/O distinction matters less when
        // the entire app is single-process.
        scope.launch(Dispatchers.Default) {
            outbox.consumeEach { json ->
                td_send(clientId, json)
            }
        }

        // Receiver pump — blocks in td_receive for up to 1s per tick, then
        // republishes any update into the SharedFlow. The 1s timeout is the
        // sweet spot used by TDLib's official Android/Java sample: short
        // enough to keep the coroutine cancellation latency tolerable, long
        // enough that we don't burn CPU on tight polling.
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                val raw = td_receive(POLL_TIMEOUT_S)?.toKStringFromUtf8() ?: continue
                _updates.emit(raw)
            }
        }
    }

    /** Enqueue a JSON-encoded request. Non-blocking. Pair via `@extra` for replies. */
    fun send(json: String) {
        outbox.trySend(json)
    }

    companion object {
        /** Seconds per td_receive poll tick — see receiver-pump KDoc above. */
        const val POLL_TIMEOUT_S: Double = 1.0

        /**
         * Synchronous TDLib request (no client id, no auth). Used for
         * `getTextEntities`, `parseTextEntities`, `getLogTags`, etc. — pure
         * helpers that don't touch network or persistence. Safe to call from
         * any thread including the main thread.
         */
        fun execute(json: String): String? = td_execute(json)?.toKStringFromUtf8()
    }
}
