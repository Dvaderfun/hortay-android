package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable

/**
 * Resolved custom-emoji sticker descriptor. Produced by
 * `CustomEmojiRepository.request → flush(GetCustomEmojiStickers)`; consumed by
 * `CustomEmojiInlineView` / `InlineCustomEmojiRenderer` to paint a single
 * inline glyph.
 *
 * [thumb] is the small-image preview Telegram ships alongside every sticker
 * (`Sticker.thumbnail`) — UI shows it the moment the sticker resolves, before
 * the animated [media] file downloads. This is the "instant preview" pattern
 * Telegram itself uses; it's the difference between a chat that pops in
 * stickers and one that flashes blank squares while the .tgs files trickle
 * over the wire.
 *
 * [needsRepainting] reflects `StickerFullTypeCustomEmoji.needsRepainting` —
 * when true, the sticker is monochrome and must be recoloured to the
 * surrounding text colour at render time (Telegram Premium emoji-status /
 * inline status pills behaviour). The renderer applies this via a Compose
 * ColorFilter.
 */
@Immutable
data class CustomEmojiSticker(
    val customEmojiId: Long,
    val media: TdMedia,
    val thumb: TdMedia?,
    val format: StickerFormat,
    val needsRepainting: Boolean,
)
