package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import dev.lyo.hortay.data.ReplyMediaKind
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.reply_kind_animation
import hortay.shared.generated.resources.reply_kind_audio
import hortay.shared.generated.resources.reply_kind_document
import hortay.shared.generated.resources.reply_kind_photo
import hortay.shared.generated.resources.reply_kind_poll
import hortay.shared.generated.resources.reply_kind_sticker
import hortay.shared.generated.resources.reply_kind_video
import hortay.shared.generated.resources.reply_kind_video_note
import hortay.shared.generated.resources.reply_kind_voice
import org.jetbrains.compose.resources.stringResource

/**
 * Single source of truth for translating [ReplyMediaKind] into a localised label and a
 * Material Symbol name. Both PostCard's quote card (feed) and CommentsScreen's quote
 * card (discussion thread) call into here so the two surfaces never drift on icons or
 * wording. Kept in `ui.timeline` because PostCard is the canonical owner of the quote
 * design language; CommentsScreen reuses by import.
 */
@Composable
internal fun ReplyMediaKind.label(): String = when (this) {
    ReplyMediaKind.None -> ""
    ReplyMediaKind.Photo -> stringResource(Res.string.reply_kind_photo)
    ReplyMediaKind.Video -> stringResource(Res.string.reply_kind_video)
    ReplyMediaKind.Animation -> stringResource(Res.string.reply_kind_animation)
    ReplyMediaKind.Document -> stringResource(Res.string.reply_kind_document)
    ReplyMediaKind.Audio -> stringResource(Res.string.reply_kind_audio)
    ReplyMediaKind.VoiceNote -> stringResource(Res.string.reply_kind_voice)
    ReplyMediaKind.VideoNote -> stringResource(Res.string.reply_kind_video_note)
    ReplyMediaKind.Sticker -> stringResource(Res.string.reply_kind_sticker)
    ReplyMediaKind.Poll -> stringResource(Res.string.reply_kind_poll)
}

internal fun ReplyMediaKind.symbolName(): String? = when (this) {
    ReplyMediaKind.None -> null
    ReplyMediaKind.Photo -> "image"
    ReplyMediaKind.Video -> "play_circle"
    ReplyMediaKind.Animation -> "gif_box"
    ReplyMediaKind.Document -> "description"
    ReplyMediaKind.Audio -> "audio_file"
    ReplyMediaKind.VoiceNote -> "mic"
    ReplyMediaKind.VideoNote -> "videocam"
    ReplyMediaKind.Sticker -> "sentiment_satisfied"
    ReplyMediaKind.Poll -> "poll"
}
