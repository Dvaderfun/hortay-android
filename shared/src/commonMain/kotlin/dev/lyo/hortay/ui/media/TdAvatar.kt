package dev.lyo.hortay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Composable contract used by [TdAvatar] to paint the high-resolution
 * fileId-driven avatar layer. Android's `AppGraph` provides an implementation
 * that routes through [TdMediaImage] (which talks to `MediaCache` /
 * `TdClient`); iOS leaves it null so the avatar collapses to letter + thumb.
 *
 * Wired via [LocalAvatarFileLoader]. Kept as a CompositionLocal slot rather
 * than an `expect/actual` so the iOS guest-mode UI doesn't pull in a
 * MediaCache abstraction that no iOS code path would ever exercise.
 */
typealias AvatarFileLoader = @Composable (
    fileId: Int,
    contentDescription: String?,
    modifier: Modifier,
) -> Unit

val LocalAvatarFileLoader = staticCompositionLocalOf<AvatarFileLoader?> { null }

/**
 * 3-tier avatar pyramid. Each tier paints over the previous so we never show
 * an empty circle:
 *
 *   1. **Initial letter** (always rendered) — synchronous, never blocks.
 *   2. **Minithumb** (~40×40 inline JPEG from TDLib's `Minithumbnail.data`,
 *      ships in the same payload as the post/sender — zero extra requests).
 *   3. **Small file** (160×160) — downloaded with `DownloadPriority.Avatar`
 *      via the platform-provided [LocalAvatarFileLoader]. Replaces the
 *      minithumb once decoded. iOS leaves the loader null, so iOS avatars
 *      stay at minithumb fidelity.
 *
 * If both `thumb` and `fileId` are null, only the letter on a coloured disc
 * renders — users / chats with no profile photo at all.
 */
@Composable
fun TdAvatar(
    name: String,
    thumb: ByteArray?,
    fileId: Int?,
    size: Dp,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
    /**
     * Optional remote URL layered over the initial-letter fallback. Used by
     * web (anonymous) mode where TDLib's [fileId] / [thumb] are unavailable
     * but a CDN avatar URL was parsed from t.me/s/. TDLib-mode callers leave
     * this null.
     */
    remoteUrl: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = textStyle,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
        )
        if (thumb != null) {
            MinithumbImage(bytes = thumb, contentDescription = name)
        }
        val loader = LocalAvatarFileLoader.current
        if (fileId != null && loader != null) {
            loader(fileId, name, Modifier.fillMaxSize())
        } else if (remoteUrl != null) {
            AsyncImage(
                model = remoteUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
