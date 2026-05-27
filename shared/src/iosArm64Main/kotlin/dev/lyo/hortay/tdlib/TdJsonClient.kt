@file:OptIn(ExperimentalForeignApi::class)

package dev.lyo.hortay.tdlib

import dev.lyo.hortay.tdlib.native.td_create_client_id
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
 * via the cinterop binding declared in `tdjson.def`.
 *
 * Dispatching shape (one client id, one receive pump, request/response
 * correlation via `@extra`) is identical to the Android `TdClient.kt`.
 *
 * Thread-safety: TDLib's tdjson is callable from any thread, but each client
 * id has a single receiver. We pump on Dispatchers.Default and fan updates
 * out via a SharedFlow that downstream collectors observe.
 */
actual class TdJsonClient actual constructor(scope: CoroutineScope) {

    /** Stable TDLib-assigned client id. Reused for every td_send/td_receive call. */
    val clientId: Int = td_create_client_id()

    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Hot stream of raw JSON updates from TDLib (broadcast). Collectors get
     * every update emitted after subscription; subscribe before the first
     * authentication query lands.
     */
    actual val updates: SharedFlow<String> = _updates.asSharedFlow()

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

        // Wake TDLib: the new multi-client API (td_create_client_id) doesn't
        // emit updates until the first td_send. A harmless getOption kicks
        // the instance alive and triggers updateAuthorizationState.
        td_send(clientId, """{"@type":"getOption","name":"version","@extra":"wake"}""")

        // Receiver pump — blocks in td_receive for up to 1s per tick, then
        // republishes any update into the SharedFlow.
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                val raw = td_receive(POLL_TIMEOUT_S)?.toKStringFromUtf8() ?: continue
                _updates.emit(raw)
            }
        }
    }

    /** Enqueue a JSON-encoded request. Non-blocking. Pair via `@extra` for replies. */
    actual fun send(json: String) {
        outbox.trySend(json)
    }

    companion object {
        /** Seconds per td_receive poll tick — see receiver-pump KDoc above. */
        const val POLL_TIMEOUT_S: Double = 1.0
    }
}
