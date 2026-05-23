package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable

/**
 * Avatar + identity payload for the "new posts" pill's overlapping channel
 * avatars row. Carries everything the pill renderer needs to draw a
 * 24×24 avatar disc per channel:
 *
 *   - TDLib mode: [thumb] (inline minithumb) + [fileId] (full file via
 *     `LocalAvatarFileLoader`). [avatarUrl] is null.
 *   - Web/guest mode: [avatarUrl] (CDN URL parsed from `t.me/s/`). [thumb]
 *     and [fileId] are null.
 *
 * [latestPostDate] is the unix seconds of the most-recent unseen post in
 * this channel; the pill uses it to order avatars left-to-right
 * (newest first).
 */
@Immutable
data class ChannelBadge(
    val chatId: Long,
    val title: String,
    val thumb: ByteArray?,
    val fileId: Int?,
    val avatarUrl: String? = null,
    val latestPostDate: Long,
)
