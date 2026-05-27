package dev.lyo.hortay.data

/** Preview snapshot returned by ChannelActionsRepository.previewChatInvite. */
data class ChatInvitePreview(
    val inviteLink: String,
    /** Resolved chat id if the user already has access (already a member); null otherwise. */
    val chatId: ChatId?,
    val title: String,
    val memberCount: Int,
    val kind: InviteLinkKind,
)

/** Discriminator on [ChatInvitePreview.kind]. Hortay only joins channels natively;
 *  group invites surface a snackbar and hand off to Telegram. */
enum class InviteLinkKind { Channel, Group }
