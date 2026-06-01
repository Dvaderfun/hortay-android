package dev.lyo.hortay.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.TdMedia
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieComposition as CompottieComposition
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.GzipSource
import okio.Path.Companion.toPath
import okio.buffer

/**
 * Plays a Telegram TGS (gzipped Lottie) sticker via Compottie. Used both
 * for full-size stickers (in PostBody / comments) and for inline
 * custom-emoji glyphs (one Composable per glyph — the old shared-bitmap
 * rasteriser that relied on Airbnb's `LottieDrawable.draw(canvas)` is
 * gone since H2). Pipeline:
 *
 *   1. Ask [dev.lyo.hortay.data.MediaCache] to download the .tgs file at
 *      the given priority.
 *   2. Underlay the [thumb] (TDLib-served WEBP/PNG) — instant preview
 *      while bytes land.
 *   3. Once the file is `Ready`, decompress + parse into Compottie's
 *      [CompottieComposition].
 *   4. Hand the composition to Compose's [Image] with [rememberLottiePainter]
 *      — Compottie's pure-Kotlin renderer, KMP-ready.
 *
 * # Recoloring (`repaintColor`)
 *
 * TDLib's `StickerFullTypeCustomEmoji.needsRepainting` flag marks
 * monochrome emojis that must take the surrounding text colour. Compottie's
 * renderer respects Compose's [ColorFilter] on the surrounding [Image] —
 * `ColorFilter.tint(color, blendMode = BlendMode.SrcAtop)` is the
 * KMP-friendly equivalent. No per-layer dynamic-property DSL needed.
 *
 * # Lifecycle / battery
 *
 * Animation pauses while the host lifecycle is below STARTED (app
 * backgrounded, screen off). Off-screen items in a LazyColumn are disposed
 * by the column itself, which tears down this composable and stops drawing
 * entirely.
 */
@Composable
fun LottieStickerView(
    fileId: Int?,
    thumb: TdMedia?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
    iterate: Boolean = true,
    repaintColor: Color? = null,
    /**
     * Web-mode TGS payload URL. When [fileId] is null and [remoteUrl] is
     * set, we bypass [dev.lyo.hortay.data.MediaCache] / TDLib entirely and
     * fetch the .tgs (or pre-decompressed JSON) bytes via
     * [LocalWebHttpClient].
     */
    remoteUrl: String? = null,
) {
    val httpClient = LocalWebHttpClient.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val isRemote = fileId == null && remoteUrl != null

    val binding = rememberMediaBinding(fileId = fileId, priority = priority, isRemote = isRemote)

    var composition by remember(fileId, remoteUrl) { mutableStateOf<CompottieComposition?>(null) }
    val readyPath = binding.readyPath
    LaunchedEffect(readyPath, remoteUrl, isRemote) {
        if (isRemote) {
            val url = remoteUrl ?: return@LaunchedEffect
            composition = loadCompottieFromUrl(url, httpClient)
        } else {
            val path = readyPath ?: return@LaunchedEffect
            composition = loadCompottieFromPath(path)
        }
    }

    val isPlaying = composition != null &&
        lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = if (iterate) Compottie.IterateForever else 1,
        isPlaying = isPlaying,
    )

    // TRANSPARENT-content reveal (see [MediaReveal] KDoc): the static thumb fades OUT as
    // the animation fades in, rather than hard-cutting when `composition` flips non-null.
    // `revealed` = the composition parsed (first real animation frame is paintable). The
    // thumb stays composed through the fade + linger via [rememberPlaceholderLinger] keyed
    // on the media identity, so an in-place sticker swap re-arms it.
    val revealed = composition != null
    val revealAlpha = rememberRevealAlpha(revealed)
    val showThumb = rememberPlaceholderLinger(revealed, key = fileId ?: remoteUrl)
    Box(modifier = modifier) {
        if (thumb != null && showThumb) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - revealAlpha }) {
                TdMediaImage(
                    media = thumb,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderColor = null,
                    showProgress = false,
                    priority = priority,
                )
            }
        }
        composition?.let { comp ->
            Image(
                painter = rememberLottiePainter(
                    composition = comp,
                    progress = { progress },
                ),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = revealAlpha },
                colorFilter = repaintColor?.let {
                    ColorFilter.tint(color = it, blendMode = BlendMode.SrcAtop)
                },
            )
        }
    }
}

/**
 * Read a TGS file from disk into a Compottie composition. Uses okio's KMP
 * [FileSystem] + [GzipSource] so the same code runs on Android (java.io
 * under the hood) and iOS (POSIX file APIs). Returns null on malformed
 * payload — caller falls back to the static thumbnail.
 */
private suspend fun loadCompottieFromPath(path: String): CompottieComposition? =
    withContext(Dispatchers.Default) {
        val json = decompressTgsFromPath(path) ?: return@withContext null
        runCatching { LottieCompositionSpec.JsonString(json).load() }.getOrNull()
    }

private suspend fun loadCompottieFromUrl(url: String, http: HttpClient): CompottieComposition? =
    withContext(Dispatchers.Default) {
        val response = runCatching {
            http.get(url) { header(HttpHeaders.UserAgent, COMPOTTIE_USER_AGENT) }
        }.getOrNull() ?: return@withContext null
        if (response.status.value !in 200..299) return@withContext null
        val bytes = response.bodyAsBytes()
        val json = decompressIfTgs(bytes) ?: return@withContext null
        runCatching { LottieCompositionSpec.JsonString(json).load() }.getOrNull()
    }

private fun decompressTgsFromPath(path: String): String? {
    val fileSource = try {
        FileSystem.SYSTEM.source(path.toPath())
    } catch (t: Throwable) {
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        return null
    }
    val gzip = GzipSource(fileSource).buffer()
    return try {
        readBoundedUtf8(gzip)
    } catch (t: Throwable) {
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        null
    } finally {
        gzip.close()
    }
}

private fun decompressIfTgs(bytes: ByteArray): String? {
    if (bytes.size > COMPOTTIE_MAX_DECOMPRESSED_BYTES) return null
    val isGzip = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
    if (!isGzip) return bytes.decodeToString()
    val src = Buffer().apply { write(bytes) }
    val gzip = GzipSource(src).buffer()
    return try {
        readBoundedUtf8(gzip)
    } catch (t: Throwable) {
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        null
    } finally {
        gzip.close()
    }
}

private fun readBoundedUtf8(source: okio.BufferedSource): String? {
    val sink = Buffer()
    var total = 0L
    while (true) {
        val read = source.read(sink, BUFFER_SIZE.toLong())
        if (read == -1L) break
        total += read
        if (total > COMPOTTIE_MAX_DECOMPRESSED_BYTES) return null
    }
    return sink.readUtf8()
}

private const val BUFFER_SIZE = 16 * 1024
private const val COMPOTTIE_MAX_DECOMPRESSED_BYTES = 5L * 1024L * 1024L
private const val COMPOTTIE_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
