@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package dev.lyo.hortay.tdlib

import dev.lyo.hortay.isDebugBuild
import dev.lyo.hortay.tdlib.native.td_create_client_id
import dev.lyo.hortay.tdlib.native.td_execute
import dev.lyo.hortay.tdlib.native.td_receive
import dev.lyo.hortay.tdlib.native.td_send
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * Thin Kotlin wrapper around TDLib's `td_json_client.h` C interface, accessed
 * via the cinterop binding declared in `tdjson.def`.
 *
 * Dispatching shape (one client id, one receive pump, request/response
 * correlation via `@extra`) mirrors the Android `TdClient.kt`, INCLUDING its
 * load-bearing buffering contract:
 *
 *   **Dedicated receive thread + UNLIMITED Channel → SharedFlow(64).**
 *   `td_receive` is a BLOCKING C call; TDLib's own docs require it on a
 *   dedicated thread. The previous implementation looped it on
 *   `Dispatchers.Default`, which on Kotlin/Native is a small bounded worker
 *   pool shared by every repository/collector/`ioDispatcher` coroutine — one
 *   pinned worker plus a SUSPEND SharedFlow meant a slow downstream collector
 *   during the cold-start update storm could back-pressure `_updates.emit`,
 *   suspend the single receive pump, and stop draining TDLib entirely
 *   (connection frozen at `Connecting`, feed stuck on skeletons). Now the
 *   blocking loop owns its own thread and hands raw JSON to an UNLIMITED
 *   inbox via a non-suspending `trySend`; a separate drain coroutine republishes
 *   into the SharedFlow. Downstream back-pressure can only grow the inbox — it
 *   can never stall `td_receive`. Same decoupling Android gets from its
 *   UNLIMITED Channel in front of the SharedFlow.
 *
 * `iosArm64Main` actual: real cinterop pump (Konan calls into the
 * statically-linked `libtdjson_static.a` from `libtdlight.xcframework`).
 */
actual class TdJsonClient actual constructor(scope: CoroutineScope) {

    /** Stable TDLib-assigned client id. Reused for every td_send/td_receive call. */
    val clientId: Int = td_create_client_id()

    private val outbox = Channel<String>(Channel.UNLIMITED)

    // UNLIMITED so the blocking receive thread never blocks on downstream
    // back-pressure — see class KDoc. The drain coroutine pulls from here into
    // the SharedFlow.
    private val inbox = Channel<String>(Channel.UNLIMITED)

    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Hot stream of raw JSON updates from TDLib (broadcast). Collectors get
     * every update emitted after subscription; subscribe before the first
     * authentication query lands.
     */
    actual val updates: SharedFlow<String> = _updates.asSharedFlow()

    // Dedicated OS thread for the blocking td_receive loop. Process-lifetime —
    // tied to [scope] (the app scope, cancelled only at process death), so it is
    // intentionally never .close()'d; it dies with the process.
    private val receiveThread = newSingleThreadContext("td-receive")

    init {
        // Quiet TDLib's default verbosity-5 firehose BEFORE creating any client
        // traffic. Synchronous global call. Release (and every iOS build, where
        // isDebugBuild is false) runs at 0 = fatal-only; debug keeps 3 for
        // field diagnosis. Mirrors Android's SetLogVerbosityLevel(LOG_VERBOSITY)
        // and honours the ARCHITECTURE LOG_VERBOSITY hard rule.
        val verbosity = if (isDebugBuild) 3 else 0
        td_execute("""{"@type":"setLogVerbosityLevel","new_verbosity_level":$verbosity}""")

        // Sender pump — funnels outbound queries to td_send. Suspends on the
        // channel (never blocks); td_send is non-blocking + reentrant so it is
        // fine on the shared Default pool.
        scope.launch(Dispatchers.Default) {
            outbox.consumeEach { json ->
                td_send(clientId, json)
            }
        }

        // Wake TDLib: the new multi-client API (td_create_client_id) doesn't
        // emit updates until the first td_send. A harmless getOption kicks
        // the instance alive and triggers updateAuthorizationState.
        td_send(clientId, """{"@type":"getOption","name":"version","@extra":"wake"}""")

        // Receiver pump — blocks in td_receive on its OWN thread, then hands the
        // raw JSON to the UNLIMITED inbox without ever suspending on downstream.
        scope.launch(receiveThread) {
            while (isActive) {
                val raw = td_receive(POLL_TIMEOUT_S)?.toKStringFromUtf8() ?: continue
                inbox.trySend(raw)
            }
        }

        // Drain pump — republishes inbox JSON into the SharedFlow on the shared
        // pool. If a slow collector back-pressures _updates, the UNLIMITED inbox
        // absorbs it; td_receive keeps draining TDLib and the connection never
        // stalls.
        scope.launch(Dispatchers.Default) {
            inbox.consumeEach { raw ->
                _updates.emit(raw)
            }
        }
    }

    /** Enqueue a JSON-encoded request. Non-blocking. Pair via `@extra` for replies. */
    actual fun send(json: String) {
        outbox.trySend(json)
    }

    companion object {
        /** Seconds per td_receive poll tick — bounded so loop cancellation stays responsive. */
        const val POLL_TIMEOUT_S: Double = 1.0
    }
}
