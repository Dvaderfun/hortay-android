package dev.lyo.hortay.data.web

import android.util.Log
import dev.lyo.hortay.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.coroutines.delay
import okhttp3.Cache
import okhttp3.ConnectionPool
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP-level access to `https://t.me/s/<channel>` for the anonymous web pipeline.
 *
 * Responsibilities:
 *   - Authenticated-looking GET to a public channel preview page.
 *   - Conditional GET via `If-Modified-Since` and `If-None-Match` to keep 200+ channel
 *     polling within reason. A 304 reply costs ~200 bytes vs ~30 KB for a full body —
 *     this is the single architectural lever that makes web-mode scale.
 *   - Global rate-limit gate: one token bucket guarding all requests, exponential
 *     backoff on 429. Mirrors [dev.lyo.hortay.data.TdClient.floodWaitUntilMs] semantics
 *     but on a per-process basis (not per-API-method) — t.me/s/ has one bucket.
 *   - Defensive parsing via [TmePageParser]; rendering layout regressions surface as
 *     [LookupResult.ParseFailure] rather than crashes.
 *
 * Migrated from raw OkHttp to Ktor HttpClient in Phase A3. The Ktor client wraps an
 * OkHttp engine on Android (preserving the disk cache + connection pool from the
 * original implementation) and a Darwin engine on iOS. Public API surface is now
 * platform-neutral so this file can move to commonMain when Phase C lands.
 *
 * User-Agent rationale: a real-looking mobile browser UA is required. Using Ktor's
 * default UA causes Telegram's edge to occasionally serve a stripped-down fallback
 * page or 4xx outright. We pick a stable Chrome-on-Linux string so anyone analyzing
 * logs sees indistinguishable browser traffic.
 */
class WebTelegramClient(
    private val httpClient: HttpClient,
) {

    /**
     * Single global deadline used by both 429 and connection-error backoff. Shared with
     * [awaitGate] before each request so concurrent fetches all suspend together rather
     * than each tripping a separate 429.
     */
    private val gateUntilMs = AtomicLong(0L)

    /**
     * Fetch one page of a channel preview.
     *
     * Conditional GET (ETag / Last-Modified) is delegated to Ktor's [HttpCache]
     * plugin on the engine side. On Android we configure the underlying OkHttp
     * engine's disk [Cache] (see [defaultHttpClient]); on iOS the engine's
     * NSURLSession URLCache handles it. 304 detection: Ktor surfaces a 200 with
     * the cached body in both engines, and exposes the wire-level `Age` /
     * `X-Cache` headers so callers can detect "cache served, no revalidation
     * needed". We approximate this via a marker header — see [handleResponse].
     *
     * @param username channel handle without leading `@`.
     * @param before pagination cursor; null for latest page.
     * @param useCache when false, set `Cache-Control: no-cache` to bypass cache.
     */
    suspend fun fetchChannelPage(
        username: String,
        before: String? = null,
        useCache: Boolean = true,
    ): FetchResult {
        awaitGate()

        val url = buildUrl(username, before)

        return runCatching {
            httpClient.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                // Ktor + OkHttp engine handle Accept-Encoding + gunzip automatically.
                // Don't set it manually — same trap as the original OkHttp impl.
                header(HttpHeaders.AcceptLanguage, ACCEPT_LANGUAGE)
                if (!useCache) {
                    header(HttpHeaders.CacheControl, "no-cache")
                }
            }
        }.fold(
            onSuccess = { response -> handleResponse(response, username) },
            onFailure = { error ->
                Log.w(TAG, "fetchChannelPage(${username}) failed: ${error.message}")
                FetchResult.NetworkError(error)
            },
        )
    }

    /**
     * Cheap "does this channel exist?" probe used by `AddChannelScreen` to validate
     * user input before subscribing.
     */
    suspend fun lookupChannel(username: String): LookupResult {
        val result = try {
            kotlinx.coroutines.withTimeout(LOOKUP_TIMEOUT_MS) {
                fetchChannelPage(username, useCache = false)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val remainingMs = (gateUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)
            return if (remainingMs > 0L) {
                LookupResult.RateLimited(remainingMs)
            } else {
                LookupResult.NetworkError(LookupTimeoutException())
            }
        }
        return when (result) {
            is FetchResult.Page -> {
                if (result.page.posts.isEmpty()) {
                    LookupResult.Empty(result.page.channel)
                } else {
                    LookupResult.Found(result.page.channel)
                }
            }
            FetchResult.NotFound -> LookupResult.NotFound
            FetchResult.PrivateChannel -> LookupResult.Private
            is FetchResult.RateLimited -> LookupResult.RateLimited(result.retryAfterMs)
            is FetchResult.NetworkError -> LookupResult.NetworkError(result.cause)
            FetchResult.NotModified -> LookupResult.NotFound
            is FetchResult.ParseFailure -> LookupResult.ParseFailure
        }
    }

    private suspend fun handleResponse(
        response: HttpResponse,
        username: String,
    ): FetchResult {
        when (response.status.value) {
            200 -> {
                val body = response.bodyAsText()
                val page = TmePageParser.parse(body, username)
                if (page == null) {
                    Log.w(TAG, "Parse failed for $username (body length=${body.length})")
                    if (AppConfig.debug) {
                        Log.w(TAG, "  head: ${body.take(400).replace('\n', ' ')}")
                        Log.w(TAG, "  ctype: ${response.headers[HttpHeaders.ContentType]} server: ${response.headers[HttpHeaders.Server]}")
                    }
                    return FetchResult.ParseFailure
                }
                return FetchResult.Page(
                    page = page,
                    etag = response.headers[HttpHeaders.ETag],
                    lastModified = response.headers[HttpHeaders.LastModified],
                )
            }
            HttpStatusCode.NotModified.value -> return FetchResult.NotModified
            HttpStatusCode.NotFound.value -> return FetchResult.NotFound
            HttpStatusCode.Forbidden.value -> return FetchResult.PrivateChannel
            in 300..399 -> {
                // Ktor follows redirects by default; we disable it in [defaultHttpClient]
                // so we can detect t.me/s/foo → t.me/foo as the "private channel" shape.
                val location = response.headers[HttpHeaders.Location].orEmpty()
                val redirectsToBareTme = location.contains("t.me/") && !location.contains("t.me/s/")
                return if (redirectsToBareTme) FetchResult.PrivateChannel
                else FetchResult.NetworkError(IOException("HTTP ${response.status.value} → $location"))
            }
            429 -> {
                val retrySec = response.headers["Retry-After"]?.toLongOrNull() ?: DEFAULT_BACKOFF_SEC
                val capped = retrySec.coerceAtMost(MAX_BACKOFF_SEC)
                pushGate(capped * 1000L)
                Log.w(TAG, "429 from t.me/s/$username — backing off ${capped}s")
                return FetchResult.RateLimited(capped * 1000L)
            }
            in 500..599 -> {
                pushGate(SERVER_ERROR_BACKOFF_SEC * 1000L)
                return FetchResult.NetworkError(IOException("HTTP ${response.status.value}"))
            }
            else -> return FetchResult.NetworkError(IOException("HTTP ${response.status.value}"))
        }
    }

    private fun buildUrl(username: String, before: String?): String =
        URLBuilder().apply {
            takeFrom("https://t.me/s/$username")
            if (before != null) parameters.append("before", before)
        }.buildString()

    private suspend fun awaitGate() {
        while (true) {
            val until = gateUntilMs.get()
            val now = System.currentTimeMillis()
            if (until <= now) return
            delay(until - now)
        }
    }

    private fun pushGate(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        gateUntilMs.updateAndGet { existing -> maxOf(existing, deadline) }
    }

    companion object {
        private const val TAG = "WebTelegram"

        // Desktop-Chrome UA. Telegram's edge serves THREE different versions of t.me/<u>:
        //   • mobile UA → ~2 KB "Open in app" landing page
        //   • Ktor / generic → ~20 KB partial template (no channel history)
        //   • desktop browser UA → ~136 KB full /s/ preview with posts
        // We need the third one. If Telegram changes their UA-sniffing rules,
        // [TmePageParser] will detect the missing .tgme_channel_info_header_title
        // and surface ParseFailure to the caller.
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private val ACCEPT_LANGUAGE: String = run {
            val tag = Locale.getDefault().toLanguageTag()
            if (tag.equals("en", ignoreCase = true) || tag.startsWith("en-", ignoreCase = true)) {
                "$tag,en;q=0.9"
            } else {
                "$tag,en;q=0.8"
            }
        }

        private const val MAX_BACKOFF_SEC = 120L
        private const val DEFAULT_BACKOFF_SEC = 30L
        private const val LOOKUP_TIMEOUT_MS = 15_000L
        private const val SERVER_ERROR_BACKOFF_SEC = 10L

        /**
         * Build the default HTTP client. Pass a [cacheDir] to enable disk-backed
         * conditional GET — typically the app's `Context.cacheDir / "web-http"`.
         * When null, no HTTP cache is used and every fetch is a full body download.
         *
         * Implementation: Ktor's `HttpClient(OkHttp)` engine takes an OkHttp.Builder
         * via `config { }`. We configure the OkHttp internals (timeouts, cache,
         * connection pool, redirect policy) there, then Ktor's HttpClient surface
         * wraps that engine. This keeps the original behaviour (disk cache,
         * 8-conn pool, no auto-redirect) while moving the public API to Ktor.
         */
        fun defaultHttpClient(cacheDir: File? = null): HttpClient = HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(15, TimeUnit.SECONDS)
                    callTimeout(20, TimeUnit.SECONDS)
                    connectionPool(ConnectionPool(8, 60, TimeUnit.SECONDS))
                    // Disable redirect-following for /s/<u> probes so we can
                    // detect the "private channel" redirect (t.me/s/foo →
                    // t.me/foo) cleanly. Ktor's HttpRedirect plugin (not
                    // installed) would otherwise follow them.
                    followRedirects(false)
                    followSslRedirects(false)
                    if (cacheDir != null) {
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        cache(Cache(cacheDir, HTTP_CACHE_SIZE_BYTES))
                    }
                }
            }
            // Ktor's HttpRedirect is on by default; turn it off because we
            // configured the engine to not follow redirects.
            followRedirects = false
            // Ktor's HttpCache works in-memory by default; the OkHttp engine's
            // disk cache (above) is the persistent layer. Installing HttpCache
            // here would shadow OkHttp's cache, so we leave it out.
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000L
            }
            install(HttpRequestRetry) {
                // Conservative retry: only on transient transport-layer failures
                // (e.g. interrupted connections, not 4xx/5xx). 429/5xx handling
                // lives in [handleResponse] where it integrates with the global
                // rate-limit gate.
                retryOnExceptionIf(maxRetries = 2) { _, cause ->
                    cause is IOException
                }
                exponentialDelay(base = 2.0, maxDelayMs = 5_000L)
            }
        }

        private const val HTTP_CACHE_SIZE_BYTES = 10L * 1024 * 1024
    }
}

/** Result of a single fetch. Covers every branch the scheduler needs to handle. */
sealed interface FetchResult {
    data class Page(
        val page: WebChannelPage,
        val etag: String?,
        val lastModified: String?,
    ) : FetchResult
    data object NotModified : FetchResult
    data object NotFound : FetchResult
    data object PrivateChannel : FetchResult
    data class RateLimited(val retryAfterMs: Long) : FetchResult
    data class NetworkError(val cause: Throwable) : FetchResult
    data object ParseFailure : FetchResult
}

class LookupTimeoutException : IOException()

sealed interface LookupResult {
    data class Found(val channel: WebChannelInfo) : LookupResult
    data class Empty(val channel: WebChannelInfo) : LookupResult
    data object NotFound : LookupResult
    data object Private : LookupResult
    data class RateLimited(val retryAfterMs: Long) : LookupResult
    data class NetworkError(val cause: Throwable) : LookupResult
    data object ParseFailure : LookupResult
}

fun parseUsernameFromInput(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    Regex("""^tg://resolve\?(?:.*&)?domain=([A-Za-z][A-Za-z0-9_]{1,31})\b""")
        .find(trimmed)
        ?.let { return it.groupValues[1].lowercase() }

    Regex("""^(?:https?://)?t\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{1,31})(?:/\d+)?/?$""")
        .find(trimmed)
        ?.let { return it.groupValues[1].lowercase() }

    val bare = trimmed.removePrefix("@")
    if (bare.matches(Regex("""[A-Za-z][A-Za-z0-9_]{1,31}"""))) return bare.lowercase()

    return null
}
