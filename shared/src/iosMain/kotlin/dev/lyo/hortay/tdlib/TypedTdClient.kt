@file:OptIn(ExperimentalSerializationApi::class)

package dev.lyo.hortay.tdlib

import dev.lyo.hortay.data.TdRpcException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
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
                        _updates.emit(decoded)
                    }
                } else {
                    _updates.emit(decoded)
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
            val response = deferred.await()
            if (response is TdApi.Error) {
                throw TdRpcException(response.code, response.message)
            }
            response as R
        } finally {
            pendingMutex.withLock { pending.remove(extra) }
        }
    }
}
