package dev.lyo.hortay.data.web

import dev.lyo.hortay.PlatformLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * Resolves Telegram custom-emoji ids to renderable assets via the public
 * `https://t.me/i/emoji/<id>.json` endpoint.
 *
 * See the original module KDoc above the constructor — discovery, rate-limit, caching
 * notes. Migrated from raw OkHttp to Ktor HttpClient in Phase A3.
 */
class WebCustomEmojiResolver(
    // No default value: AppGraph passes the shared client. Avoids accidental
    // second client without disk cache.
    private val httpClient: HttpClient,
) {

    private val cache = object : LinkedHashMap<String, ResolvedEmoji>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ResolvedEmoji>?): Boolean =
            size > CACHE_LIMIT
    }
    private val cacheMutex = Mutex()
    private val gateUntilMs = AtomicLong(0L)

    suspend fun resolve(emojiId: String): ResolvedEmoji? {
        cacheMutex.withLock { cache[emojiId] }?.let { return it }

        awaitGate()
        val resolved = runCatching {
            httpClient.get("https://t.me/i/emoji/$emojiId.json") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
            }
        }.fold(
            onSuccess = { response -> parseResponse(response, emojiId) },
            onFailure = { error ->
                PlatformLog.w(TAG, "resolve($emojiId) network error: ${error.message}")
                null
            },
        )

        if (resolved != null) {
            cacheMutex.withLock { cache[emojiId] = resolved }
        }
        return resolved
    }

    suspend fun resolveAll(emojiIds: Set<String>): Map<String, ResolvedEmoji> = coroutineScope {
        if (emojiIds.isEmpty()) return@coroutineScope emptyMap()
        emojiIds
            .map { id -> id to async { resolve(id) } }
            .mapNotNull { (id, deferred) -> deferred.await()?.let { id to it } }
            .toMap()
    }

    private suspend fun parseResponse(response: HttpResponse, emojiId: String): ResolvedEmoji? {
        if (response.status.value != 200) {
            PlatformLog.w(TAG, "resolve($emojiId) HTTP ${response.status.value}")
            return null
        }
        val body = response.bodyAsText()
        val json = runCatching { JSON.decodeFromString<EmojiJson>(body) }.getOrNull()
        if (json == null) {
            PlatformLog.w(TAG, "resolve($emojiId) JSON parse failed (body=${body.take(200)})")
            return null
        }
        if (json.error != null) {
            PlatformLog.w(TAG, "resolve($emojiId) endpoint error: ${json.error}")
            return null
        }
        val type = when (json.type) {
            "tgs" -> ResolvedEmoji.Type.Tgs
            "webm" -> ResolvedEmoji.Type.Webm
            "webp" -> ResolvedEmoji.Type.Webp
            else -> {
                PlatformLog.w(TAG, "resolve($emojiId) unknown type ${json.type}")
                return null
            }
        }
        val url = json.emoji ?: run {
            PlatformLog.w(TAG, "resolve($emojiId) no emoji url field")
            return null
        }
        return ResolvedEmoji(
            emojiId = emojiId,
            type = type,
            url = url,
            thumbUrl = json.thumb,
            sizePx = json.size ?: when (type) {
                ResolvedEmoji.Type.Tgs -> 512
                else -> 100
            },
        )
    }

    private suspend fun awaitGate() {
        val now = System.currentTimeMillis()
        val deadline = gateUntilMs.updateAndGet { existing -> maxOf(existing, now) + REQUEST_SPACING_MS }
        val wait = deadline - now - REQUEST_SPACING_MS
        if (wait > 0) delay(wait)
    }

    suspend fun clearCache() {
        cacheMutex.withLock { cache.clear() }
    }

    @Serializable
    private data class EmojiJson(
        val type: String? = null,
        val emoji: String? = null,
        val thumb: String? = null,
        val path: String? = null,
        val size: Int? = null,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "WebCustomEmoji"
        private const val REQUEST_SPACING_MS = 100L
        private const val CACHE_LIMIT = 1024
        private val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

data class ResolvedEmoji(
    val emojiId: String,
    val type: Type,
    val url: String,
    val thumbUrl: String?,
    val sizePx: Int,
) {
    enum class Type { Tgs, Webm, Webp }
}
