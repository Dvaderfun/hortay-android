package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable

/** Data bundle backing the channel info bottom sheet. */
@Immutable
data class ChannelInfo(
    val chatId: Long,
    val title: String,
    val handle: String?,
    val description: String?,
    val subscribers: Int?,
    val isMuted: Boolean,
    val isMember: Boolean,
)

/**
 * Data bundle backing the user-profile bottom sheet. Resolved on-entry by
 * `ChannelActionsRepository.userProfile` — three coalesced TDLib calls land here.
 * Fields are nullable when TDLib does not surface them (e.g. bot users never
 * have a birthdate; non-Premium users with no linked channel leave
 * [personalChannel] null).
 */
@Immutable
data class UserProfile(
    val userId: Long,
    val displayName: String,
    /** `@username` (with leading `@`); null when the user hasn't picked a public handle. */
    val handle: String?,
    /** Inline JPEG (~40×40) — instant placeholder, no download. */
    val avatarThumb: ByteArray?,
    /** ProfilePhoto.small.id (160×160). Downloaded at avatar priority by the renderer. */
    val avatarFileId: Int?,
    val verification: SenderVerification?,
    val isPremium: Boolean,
    val isBot: Boolean,
    val isSupport: Boolean,
    val isContact: Boolean,
    val status: PresenceStatus,
    val bio: String?,
    val birthMonth: Int?,
    val birthDay: Int?,
    val birthYear: Int?,
    val groupsInCommon: Int,
    val personalChannel: PersonalChannelLink?,
    /** Bot description / short-description (whichever TDLib surfaces). Null for human users. */
    val botDescription: String?,
)

/**
 * Premium "personal channel" linked to a user profile. Tapping the row drills the
 * channel-overlay screen inside Hortay — same path as a regular subscribed channel.
 */
@Immutable
data class PersonalChannelLink(
    val chatId: Long,
    val title: String,
    val handle: String?,
    val avatarThumb: ByteArray?,
    val avatarFileId: Int?,
    val subscribers: Int?,
)

/**
 * UI-facing collapse of TdApi.UserStatus. Reader app: we surface the bucket
 * Telegram shows, never the precise minute. [Offline.wasOnlineSeconds] is the
 * original Unix timestamp from TDLib so the renderer can format
 * "5 хв тому" / "вчора" / "10 травня" with the user's locale.
 */
sealed interface PresenceStatus {
    data object Online : PresenceStatus
    data object Recently : PresenceStatus
    data object LastWeek : PresenceStatus
    data object LastMonth : PresenceStatus
    data class Offline(val wasOnlineSeconds: Long) : PresenceStatus
    data object Empty : PresenceStatus
}
