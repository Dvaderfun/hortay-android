package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Render a TDLib `Minithumbnail.data` payload (~40×40 inline JPEG that ships
 * inside the same `UpdateNewMessage` / `GetChat` / `GetUser` response that gave
 * us the post or sender — no extra file download).
 *
 * Delegates to Coil's [AsyncImage] with a ByteArray model. Coil 3 decodes the
 * bytes asynchronously on its own dispatcher and keeps the result in its
 * memory cache, so a repeat mount with the same payload renders synchronously
 * from cache.
 *
 * The request carries a short [crossfade]: on a cache MISS (first decode) the
 * decoded bitmap fades in instead of hard-popping over the parent's letter
 * fallback (the "avatar pop"); on a cache HIT Coil renders synchronously and the
 * crossfade is a no-op, so repeat avatars during scroll stay instant.
 */
@Composable
fun MinithumbImage(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalPlatformContext.current
    val request = remember(bytes, context) {
        ImageRequest.Builder(context)
            .data(bytes)
            .crossfade(MINITHUMB_CROSSFADE_MS)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize(),
        contentScale = contentScale,
    )
}

private const val MINITHUMB_CROSSFADE_MS = 180
