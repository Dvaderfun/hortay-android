package dev.lyo.hortay.ui.media

import android.util.Log
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream

/**
 * URL-keyed counterpart of [LottieCompositionStore]. Used by guest (anonymous)
 * mode where TGS Lottie compositions arrive as remote URLs from
 * `https://t.me/i/emoji/<id>.json` rather than as local TDLib files.
 *
 * Pipeline: HEAD over HTTP → gunzip → JSON parse → cache. Same security envelope
 * as the file-backed store: gzip decompression is bounded by [MAX_DECOMPRESSED_BYTES]
 * to defuse zip-bomb shaped payloads, parse failures land as null and the renderer
 * falls back to the static thumbnail.
 *
 * Why a separate store instead of routing through TDLib's MediaCache: web-mode TGS
 * URLs carry a signed `?token=` that already provides cache-busting on the CDN side;
 * MediaCache is a TDLib-file orchestrator with no concept of opaque URLs. A small
 * dedicated cache keyed on the URL is the cheapest "make this work today" path.
 */
internal object LottieUrlStore {

    private val lru = object : LinkedHashMap<String, LottieComposition>(MAX_ENTRIES, 0.75f, /* accessOrder */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LottieComposition>?): Boolean =
            size > MAX_ENTRIES
    }

    suspend fun load(url: String, http: HttpClient): LottieComposition? {
        synchronized(lru) { lru[url]?.let { return it } }
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { fetch(url, http) }.getOrElse {
                if (it is kotlin.coroutines.cancellation.CancellationException) throw it
                Log.w(TAG, "lottie URL fetch failed: $url", it); null
            } ?: return@withContext null
            val json = decompressIfTgs(bytes) ?: return@withContext null
            val result = LottieCompositionFactory.fromJsonStringSync(json, /* cacheKey */ null)
            val composition = result.value ?: run {
                Log.w(TAG, "lottie parse failed for $url: ${result.exception?.message}")
                return@withContext null
            }
            synchronized(lru) { lru[url] = composition }
            composition
        }
    }

    private suspend fun fetch(url: String, http: HttpClient): ByteArray? {
        val response = http.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (response.status.value !in 200..299) {
            Log.w(TAG, "lottie URL HTTP ${response.status.value}: $url")
            return null
        }
        return response.bodyAsBytes()
    }

    /**
     * The t.me emoji JSON endpoint already returns plain Lottie JSON (no gzip) for
     * the `tgs` type — it pre-decompresses the asset for browsers. Older TDLib /
     * downloader paths keep the .tgs gzip wrapper. Sniff the first two bytes for
     * the gzip magic (1F 8B) and decompress only when needed; otherwise treat the
     * payload as UTF-8 JSON directly. The size cap applies to the decompressed
     * length in either case.
     */
    private fun decompressIfTgs(bytes: ByteArray): String? {
        if (bytes.size > MAX_DECOMPRESSED_BYTES) {
            // Even raw JSON over the cap is suspicious — refuse rather than parse.
            Log.w(TAG, "lottie URL payload exceeds cap (${bytes.size} > $MAX_DECOMPRESSED_BYTES)")
            return null
        }
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
        if (!isGzip) return String(bytes, Charsets.UTF_8)
        return try {
            val out = ByteArrayOutputStream(64 * 1024)
            GZIPInputStream(bytes.inputStream(), 16 * 1024).use { gis ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = gis.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DECOMPRESSED_BYTES) {
                        Log.w(TAG, "lottie URL gzip exceeds cap ($total > $MAX_DECOMPRESSED_BYTES)")
                        return null
                    }
                    out.write(buf, 0, read)
                }
            }
            out.toString(Charsets.UTF_8.name())
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Log.w(TAG, "lottie URL gzip decode failed", t)
            null
        }
    }

    private const val TAG = "LottieUrlStore"
    private const val MAX_ENTRIES = 64
    private const val MAX_DECOMPRESSED_BYTES = 5L * 1024L * 1024L
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
}
