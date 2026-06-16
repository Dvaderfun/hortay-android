@file:OptIn(ExperimentalSerializationApi::class)

package dev.lyo.hortay.tdlib

import dev.lyo.hortay.data.TdRpcException
import dev.lyo.hortay.data.floodWaitSeconds
import dev.lyo.hortay.data.isFloodWaitCode
import dev.lyo.hortay.nowMs
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed wrapper around [TdJsonClient]: encodes/decodes [TdApi] objects via
 * kotlinx.serialization, correlates requests with responses through TDLib's
 * `@extra` discriminator, and exposes incoming updates as a typed flow.
 *
 * Mirrors the Android `TdClient`'s `suspend send` surface — the eventual
 * commonMain `TdSender` actual on iOS delegates to this client. Phase II-D
 * landing point for typed iOS RPC; the lift of `PostsRepository` to
 * commonMain (Phase II-D1) builds on top.
 *
 * Thread-safety: tdjson is reentrant; this client serialises sends through
 * [TdJsonClient]'s outbox channel. Concurrent `send` calls from multiple
 * coroutines are safe because each gets its own `@extra` key and its own
 * `CompletableDeferred`.
 */
class TypedTdClient(scope: CoroutineScope) {

    private val raw = TdJsonClient(scope)

    /**
     * Single JSON codec configured for TDLib's wire format:
     *   - `@type` is the polymorphic discriminator (set on `TdApi.Object`).
     *   - Unknown keys are tolerated (TDLib schema bumps may add fields).
     *   - Defaults are NOT emitted on encode — request payloads stay minimal
     *     and TDLib applies its own defaults server-side.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val extraCounter = atomic(0L)
    private val pending = HashMap<String, CompletableDeferred<TdApi.Object>>()
    private val pendingMutex = Mutex()

    // replay=1 keeps the most-recent update visible to late subscribers — Koin
    // creates this client (createdAtStart) and the IosTdAuthStateMachine
    // collector binds asynchronously, so the very first authorisation update
    // (especially the synthetic WaitPhoneNumber from the simulator stub) could
    // otherwise outrun the subscription. The extraBufferCapacity of 64 leaves
    // headroom for the cold-start burst on device builds (TDLib emits hundreds
    // of UpdateNewChat / UpdateChatLastMessage / UpdateUser within seconds of
    // logging in).
    private val _updates = MutableSharedFlow<TdApi.Object>(replay = 1, extraBufferCapacity = 64)

    /** Decoded updates from TDLib that did NOT carry an `@extra` correlation. */
    val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    // UNLIMITED decoupling buffer between the decode loop and the SharedFlow
    // fan-out — the typed twin of TdJsonClient's inbox, and load-bearing for
    // the same reason Android fronts its SharedFlow with an UNLIMITED Channel:
    //
    //   `_updates.emit` SUSPENDS while the slowest subscriber lags more than
    //   64 updates behind. Several PostsRepository collectors call back into
    //   `send()` from inside their `collect` blocks (ingest → album-coalesce
    //   GetMessage probes, MessageMapper → GetUser/GetSupergroup). On Android
    //   that is safe — JNI delivers RPC responses through a per-request
    //   handler, independent of the updates stream. Here both rode the SAME
    //   decode loop, so during a many-channel cold-start storm the pipeline
    //   deadlocked: a collector suspends awaiting its RPC response → the
    //   SharedFlow buffer fills → the decode loop suspends in `emit` → the
    //   response JSON queued behind those updates is never decoded → the
    //   collector never resumes. Symptom: feed frozen on "Connecting…" +
    //   skeletons forever, only on accounts with many channels/folders
    //   (small accounts never filled the 64-slot buffer).
    //
    //   The decode loop now completes `@extra` responses and trySends updates
    //   here — it can never suspend on downstream back-pressure. A separate
    //   drain coroutine republishes into the SharedFlow; collector lag only
    //   grows this channel.
    private val typedInbox = Channel<TdApi.Object>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.Default) {
            typedInbox.consumeEach { decoded ->
                _updates.emit(decoded)
            }
        }
    }

    // Global FLOOD_WAIT gate (mirrors Android's TdClient single AtomicLong
    // deadline). TDLib answers a throttled call with 420 (FLOOD_WAIT_<n>) or
    // 429 (retry after <n>); once tripped, every subsequent send waits out the
    // deadline instead of compounding the breach. Exposed so the auth machine /
    // HortayBackend can render the countdown banner.
    private val floodWaitUntil = atomic(0L)
    private val _floodWaitUntilMs = MutableStateFlow(0L)
    val floodWaitUntilMs: StateFlow<Long> = _floodWaitUntilMs.asStateFlow()

    init {
        scope.launch(Dispatchers.Default) {
            raw.updates.collect { rawJson ->
                val element = json.parseToJsonElement(rawJson)
                val obj = element.jsonObject
                val extra = obj["@extra"]?.jsonPrimitive?.contentOrNull

                val decoded = try {
                    json.decodeFromJsonElement<TdApi.Object>(element)
                } catch (t: Throwable) {
                    val type = obj["@type"]?.jsonPrimitive?.contentOrNull
                    dev.lyo.hortay.PlatformLog.w("TypedTdClient", "Failed to decode @type=$type: ${t.message}")
                    return@collect
                }

                if (extra != null) {
                    val deferred = pendingMutex.withLock { pending.remove(extra) }
                    if (deferred != null) {
                        deferred.complete(decoded)
                    } else {
                        typedInbox.trySend(decoded)
                    }
                } else {
                    typedInbox.trySend(decoded)
                }
            }
        }
    }

    /**
     * Send a typed RPC and suspend until the matching response lands. The
     * generic return type [R] is the function's declared result class
     * (`TdApi.SetAuthenticationPhoneNumber : Function<Ok>` → returns `Ok`).
     *
     * Cancellation propagates: if the calling coroutine cancels mid-flight,
     * the pending slot is removed so a late response is dropped silently
     * (not delivered to a dead waiter, not leaked into [updates]).
     *
     * Errors: TDLib answers any failed call with a `TdApi.Error` carrying the
     * MTProto numeric code + SCREAMING_SNAKE token. We translate that to a
     * [TdRpcException] thrown from this call site, mirroring the Android
     * `TdClient.TdException` contract so commonMain code can catch one type.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <R : TdApi.Object> send(query: TdApi.Function<R>): R {
        awaitFloodGate()

        val extra = extraCounter.incrementAndGet().toString()
        val deferred = CompletableDeferred<TdApi.Object>()
        pendingMutex.withLock { pending[extra] = deferred }

        // Encode polymorphically against the sealed Object root so the
        // `@type` discriminator lands automatically, then splice in @extra
        // by rebuilding the JsonObject (kotlinx.serialization has no native
        // hook for per-call extra fields).
        val baseElement = json.encodeToJsonElement<TdApi.Object>(query).jsonObject
        val withExtra = JsonObject(baseElement + ("@extra" to JsonPrimitive(extra)))
        raw.send(json.encodeToString(JsonObject.serializer(), withExtra))

        return try {
            // withTimeout guards against a wedged daemon / dropped response
            // leaking a permanently suspended coroutine (e.g. a refreshLocked
            // GetChats issued while the connection is stuck). Cancellation is
            // cooperative — the finally below still frees the pending slot.
            val response = withTimeout(SEND_TIMEOUT_MS) { deferred.await() }
            if (response is TdApi.Error) {
                if (isFloodWaitCode(response.code)) registerFloodWait(response.message)
                throw TdRpcException(response.code, response.message)
            }
            response as R
        } finally {
            pendingMutex.withLock { pending.remove(extra) }
        }
    }

    /** Suspend until any active FLOOD_WAIT deadline has elapsed. */
    private suspend fun awaitFloodGate() {
        val wait = floodWaitUntil.value - nowMs()
        if (wait > 0) delay(wait)
    }

    /**
     * Arm the flood gate from a TDLib rate-limit error. Monotonic — never
     * shortens a longer in-flight deadline (concurrent sends can each surface a
     * FLOOD_WAIT; the largest wins).
     */
    private fun registerFloodWait(message: String?) {
        val seconds = floodWaitSeconds(message) ?: return
        val deadline = nowMs() + seconds * 1000L
        while (true) {
            val current = floodWaitUntil.value
            if (deadline <= current) return
            if (floodWaitUntil.compareAndSet(current, deadline)) {
                _floodWaitUntilMs.value = deadline
                return
            }
        }
    }

    private companion object {
        /**
         * Defense-in-depth send timeout. Generous enough for legitimately slow
         * cold-cache calls (large GetMessageThreadHistory / SearchMessages);
         * past it the daemon is the problem, not the call. Matches Android's
         * SEND_TIMEOUT_MS.
         */
        private const val SEND_TIMEOUT_MS = 60_000L
    }
}
