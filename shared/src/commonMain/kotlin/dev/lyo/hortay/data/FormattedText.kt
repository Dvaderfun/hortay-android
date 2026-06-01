package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable

/**
 * UI-friendly text with span metadata, decoupled from `TdApi.FormattedText`.
 *
 * Telegram supports a wide entity set; we map the visually-meaningful subset and
 * collapse niche ones (cashtag, bank-card, phone-number) into [Span.Plain] — they still
 * appear as text but without click affordance for now.
 */
@Immutable
data class FormattedText(val text: String, val spans: List<Span>) {

    companion object {
        val Empty = FormattedText("", emptyList())
        fun plain(text: String) = FormattedText(text, emptyList())
    }

    @Immutable
    data class Span(val start: Int, val end: Int, val style: Style)

    @Immutable
    sealed interface Style {
        data object Bold : Style
        data object Italic : Style
        data object Underline : Style
        data object Strikethrough : Style
        data object Code : Style
        data class Pre(val language: String?) : Style
        data class TextUrl(val url: String) : Style
        data object Url : Style
        data object Mention : Style
        data class MentionName(val userId: UserId) : Style
        data object Hashtag : Style
        data object BotCommand : Style
        data object Spoiler : Style
        data class CustomEmoji(val emojiId: Long) : Style
        /**
         * Paragraph-level block quote. [expandable] mirrors TDLib's
         * `TextEntityTypeExpandableBlockQuote` (collapsible-by-default quote) — the
         * renderer previews an expandable quote at a few lines with a chevron toggle.
         */
        data class BlockQuote(val expandable: Boolean = false) : Style
    }
}
