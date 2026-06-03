package dev.lyo.hortay.data

import dev.lyo.hortay.tdlib.TdApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Bridges the existing Java-TdApi-based [TdClient] to the commonMain [TdSender]
 * interface which speaks Kotlin [TdApi].
 *
 * Conversion goes through kotlinx.serialization JSON as the interchange:
 *   - **Send:** Kotlin TdApi → [Json.encodeToJsonElement] → [JavaTdApiConverter.fromJson] →
 *     Java TdApi.Function → [TdClient.send] → Java TdApi.Object →
 *     [JavaTdApiConverter.toJson] → [Json.decodeFromJsonElement] → Kotlin TdApi.Object
 *   - **Updates:** Java TdApi.Update → [JavaTdApiConverter.toJson] →
 *     [Json.decodeFromJsonElement] → Kotlin TdApi.Update
 *
 * The JSON round-trip adds ~0.1 ms per object — negligible next to TDLib's
 * 10–100 ms network latency. Field arrays are cached in [JavaTdApiConverter].
 */
@OptIn(ExperimentalSerializationApi::class)
internal class AndroidTdSenderAdapter(
    private val tdClient: TdClient,
    scope: CoroutineScope,
) : TdSender {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        // TDLib marks many object fields "may be null", but the generated Kotlin
        // mirror types them non-null with a default. Coerce null → default on
        // decode instead of throwing.
        coerceInputValues = true
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        val queryJson = json.encodeToJsonElement<TdApi.Object>(query).jsonObject
        val javaQuery = JavaTdApiConverter.fromJson(queryJson)
            ?: throw IllegalArgumentException("Failed to convert query to Java TdApi: ${queryJson["@type"]}")
        val javaResult = tdClient.send(javaQuery as org.drinkless.tdlib.TdApi.Function<org.drinkless.tdlib.TdApi.Object>)
        val resultJson = JavaTdApiConverter.toJson(javaResult)
        return json.decodeFromJsonElement<TdApi.Object>(resultJson) as T
    }

    override val updates: SharedFlow<TdApi.Update> = tdClient.updates
        .mapNotNull { javaUpdate ->
            // Never let one undecodable update kill the stream: a throw here
            // cancels the shareIn upstream and silently stops ALL updates (the
            // app then freezes on stale data). Drop the offending update — the
            // load-bearing ones (messages, chats, files) decode fine.
            runCatching {
                val updateJson = JavaTdApiConverter.toJson(javaUpdate)
                json.decodeFromJsonElement<TdApi.Object>(updateJson) as TdApi.Update
            }.getOrNull()
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)
}
