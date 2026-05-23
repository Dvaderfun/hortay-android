package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * Render a TDLib `Minithumbnail.data` payload (~40×40 inline JPEG that ships
 * inside the same `UpdateNewMessage` / `GetChat` / `GetUser` response that gave
 * us the post or sender — no extra file download).
 *
 * Delegates to Coil's [AsyncImage] with a ByteArray model. Coil 3 decodes the
 * bytes asynchronously on its own dispatcher and keeps the result in its
 * memory cache, so a repeat mount with the same payload renders synchronously
 * from cache.
 */
@Composable
fun MinithumbImage(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncImage(
        model = bytes,
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize(),
        contentScale = contentScale,
    )
}
