package dev.lyo.hortay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.CustomEmojiSticker
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.StickerFormat

/**
 * Compact renderer for a Telegram `custom_emoji_id`. Used in two places:
 *
 *   • Inline inside [androidx.compose.foundation.text.BasicText] via
 *     [androidx.compose.ui.text.InlineTextContent] — sticker-emoji embedded
 *     in formatted post text.
 *   • Inside reaction chips when the bucket is a custom-emoji reaction.
 *
 * Battery-conscious by default: at the small sizes where this view is used
 * (≤ 28dp), driving a 30 fps WebM decoder per emoji is wasteful. So we
 * render the static [CustomEmojiSticker.thumb] for WebM and static-WEBP
 * custom emojis, and only run a full Lottie animation for TGS (Lottie is
 * GPU-cheap even at thumbnail size). Pass `animateAlways = true` in the
 * rare cases where animated playback is wanted (e.g. a focused selection
 * state).
 *
 * `tintFromText`: if the sticker is monochrome (`needsRepainting`), it's
 * tinted with [tintColor] so the glyph reads on top of any surface — same
 * way the official Telegram client renders monochrome emoji-status icons.
 */
@Composable
fun CustomEmojiInlineView(
    customEmojiId: Long,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tintColor: Color? = null,
    animateAlways: Boolean = false,
    priority: DownloadPriority = DownloadPriority.Avatar,
    /**
     * Optional pre-resolved sticker. When supplied (typical
     * FormattedTextRenderer call site: one collector + one map lookup at
     * the parent, sticker pushed down to every inline slot), this
     * composable skips its own `collectAsState` + `derivedStateOf` pair.
     * A 30-inline-emoji post becomes 1 Flow collector instead of 30 — the
     * dominant source of UI-thread overhead under scroll, measured via
     * thread sampling.
     *
     * Callers that don't have a parent-level resolver (reaction chips on a
     * PostCard, one-off uses elsewhere) leave this null and pay the
     * per-call collector cost; at 1–3 instances per surface that's fine.
     */
    preResolvedSticker: CustomEmojiSticker? = null,
) {
    val repo = LocalCustomEmoji.current

    val sticker: CustomEmojiSticker? = if (preResolvedSticker != null) {
        preResolvedSticker
    } else {
        LaunchedEffect(customEmojiId) { repo.request(listOf(customEmojiId)) }
        val storeState = repo.stickers.collectAsStateWithLifecycle()
        val resolvedState = remember(customEmojiId) {
            derivedStateOf { storeState.value[customEmojiId] }
        }
        resolvedState.value
    }

    // First fileId that actually has to be ready before SOMETHING paints in
    // the sticker box — i.e. the file the user perceives as "the emoji
    // loading":
    //   • Tgs: the static thumb if TDLib gave us one ([LottieStickerView]
    //     underlays it while .tgs streams), else the .tgs media itself.
    //   • Webp: the WEBP image at media.fileId.
    //   • Webm: the static thumb at inline size; if animateAlways is on
    //     (focused picker context) and there's no thumb, fall back to the
    //     .webm media so the placeholder waits on the actual playback file
    //     rather than hiding before the player has a frame to draw.
    // Web mode passes null fileIds and uses the remoteUrl chain instead.
    val firstVisibleFileId: Int? = sticker?.let {
        when (it.format) {
            StickerFormat.Tgs -> it.thumb?.fileId ?: it.media.fileId
            StickerFormat.Webp -> it.media.fileId
            StickerFormat.Webm -> {
                val canAnimate = animateAlways && it.media.fileId != null
                if (canAnimate) it.thumb?.fileId ?: it.media.fileId
                else it.thumb?.fileId
            }
        }
    }
    val binding = rememberMediaBinding(fileId = firstVisibleFileId, priority = priority)

    val contentReady = when {
        sticker == null -> false
        sticker.format == StickerFormat.Webm &&
            sticker.thumb == null &&
            !(animateAlways && sticker.media.fileId != null) -> false
        firstVisibleFileId == null -> true
        else -> binding.isReady
    }
    val needsPlaceholder = !contentReady

    Box(modifier = modifier) {
        if (sticker == null) {
            PlaceholderDisc()
            return@Box
        }

        val repaint = if (sticker.needsRepainting) tintColor else null

        when (sticker.format) {
            // TGS routes through [LottieStickerView] (Compottie). Each inline
            // emoji gets its own LottieAnimation — the shared-bitmap
            // optimisation that existed during the Airbnb-Lottie era is gone
            // (Compottie has no `LottieDrawable.draw(canvas)` equivalent).
            // Per-glyph rendering is fine for the realistic load (≤ 30
            // inline emojis per visible card viewport, capped by LazyColumn
            // skippability) and buys iOS parity.
            //
            // `remoteUrl` path: web (anonymous) mode where we have a URL
            // but no TDLib fileId. LottieStickerView fetches the .tgs (or
            // pre-decompressed JSON) bytes via the shared HTTP client.
            StickerFormat.Tgs -> LottieStickerView(
                fileId = sticker.media.fileId,
                thumb = sticker.thumb,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                priority = priority,
                iterate = true,
                repaintColor = repaint,
                remoteUrl = sticker.media.takeIf { it.fileId == null }?.remoteUrl,
            )
            StickerFormat.Webp -> {
                // Static WEBP is the cheap path — render directly. (Most
                // custom emojis ship as WEBP; only the animated set uses
                // TGS/Webm.)
                TdMediaImage(
                    media = sticker.media,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderColor = null,
                    showProgress = false,
                    priority = priority,
                )
            }
            StickerFormat.Webm -> {
                // WebM custom emojis ALWAYS render as the static WEBP thumb
                // at inline size. The t.me/i/emoji endpoint serves WebMs
                // pre-rendered as `yuv420p` (no alpha channel) baked against
                // `srgb(0,0,0)`; the original sticker carries alpha as a
                // sidecar VP9 stream via Matroska BlockAdditional which
                // standard Android MediaCodec ignores. A luma-key shader
                // workaround collapses for stickers with dark glyph regions
                // (a black pupil, a navy outline); adding `media3-decoder-vp9`
                // would be ~10 MB of native libs just to animate inline
                // emojis at 24 dp. The thumb is a single Coil-cached lookup,
                // appears instantly, no animation but no artifacts.
                //
                // `animateAlways = true` keeps the TDLib-mode escape hatch
                // for a focused picker UI where the file genuinely carries
                // alpha (HW VP9-alpha or a libvpx ext build). Guest mode
                // never opts into it because the URL-only sticker has no
                // alpha to recover.
                val canAnimateTdlib = animateAlways && sticker.media.fileId != null
                if (canAnimateTdlib) {
                    WebmStickerPlayer(
                        fileId = sticker.media.fileId,
                        thumb = sticker.thumb,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        priority = priority,
                    )
                } else if (sticker.thumb != null) {
                    TdMediaImage(
                        media = sticker.thumb,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        placeholderColor = null,
                        showProgress = false,
                        priority = priority,
                    )
                }
            }
        }

        // Overlay placeholder: covers the in-flight TdMediaImage /
        // LottieStickerView until the first visible file lands as Ready.
        // Kept on TOP (not as an underlay) so it doesn't bleed through
        // transparent corners of irregular glyphs once content paints.
        if (needsPlaceholder) {
            PlaceholderDisc()
        }
    }
}

@Composable
private fun BoxScope.PlaceholderDisc() {
    Box(
        modifier = Modifier
            .fillMaxSize(0.66f)
            .align(Alignment.Center)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)),
    )
}
