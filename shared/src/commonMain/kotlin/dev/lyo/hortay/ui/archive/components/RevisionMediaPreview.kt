package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.lyo.hortay.data.archive.ArchivedMediaRef
import dev.lyo.hortay.data.archive.ArchivedMediaStore
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.revision_media_audio_caption
import hortay.shared.generated.resources.revision_media_document
import hortay.shared.generated.resources.revision_media_low_res_overlay
import hortay.shared.generated.resources.revision_media_unavailable
import hortay.shared.generated.resources.revision_media_video_caption
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.stringResource

/**
 * Tri-state media preview for an archived revision:
 *   1. **Best**: file in archive storage → render full-res from disk via Coil.
 *   2. **Fallback**: only the inline minithumb survived → render the small JPEG, blurred.
 *   3. **Nothing**: a "media wasn't captured" placeholder.
 *
 * Aspect from [ArchivedMediaRef.width]/[height], else 16:9. Non-image types render a descriptor
 * row only. Coil decodes the minithumb ByteArray cross-platform (no `BitmapFactory`); existence
 * checks go through okio (no `java.io.File`).
 */
@Composable
fun RevisionMediaPreview(
    media: ArchivedMediaRef,
    mediaStore: ArchivedMediaStore?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (media.type) {
            "photo", "video", "animation", "videoNote" -> {
                ImageOrThumb(media = media, mediaStore = mediaStore)
                if (media.type == "video" || media.type == "animation" || media.type == "videoNote") {
                    Text(
                        text = stringResource(Res.string.revision_media_video_caption, media.durationMs / 1000L),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            "document" -> Text(
                text = media.fileName ?: stringResource(Res.string.revision_media_document),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            "audio", "voice" -> Text(
                text = stringResource(Res.string.revision_media_audio_caption, media.durationMs / 1000L),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun ImageOrThumb(media: ArchivedMediaRef, mediaStore: ArchivedMediaStore?) {
    val ratio = remember(media) {
        if (media.width > 0 && media.height > 0) {
            media.width.toFloat() / media.height.toFloat()
        } else {
            16f / 9f
        }
    }
    val archivedPath by produceState<String?>(initialValue = null, media.localArchiveSha, mediaStore) {
        val sha = media.localArchiveSha
        value = if (sha != null && mediaStore != null) {
            mediaStore.pathFor(sha)?.takeIf { FileSystem.SYSTEM.exists(it.toPath()) }
        } else {
            null
        }
    }
    val context = LocalPlatformContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.4f, 3f))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val thumbBytes = media.minithumbBytes
        when {
            archivedPath != null -> AsyncImage(
                model = ImageRequest.Builder(context).data("file://$archivedPath").build(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Crop,
            )
            thumbBytes != null -> {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(thumbBytes).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().blur(8.dp),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    text = stringResource(Res.string.revision_media_low_res_overlay),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
            else -> Text(
                text = stringResource(Res.string.revision_media_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
