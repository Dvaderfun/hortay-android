package dev.lyo.hortay.data

import kotlinx.coroutines.flow.StateFlow
import dev.lyo.hortay.tdlib.TdApi
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.op_change_reaction
import hortay.shared.generated.resources.op_close_poll
import hortay.shared.generated.resources.op_join_channel
import hortay.shared.generated.resources.op_leave_channel
import hortay.shared.generated.resources.op_mute
import hortay.shared.generated.resources.op_unmute
import hortay.shared.generated.resources.op_vote_in_poll

/**
 * Stateless TDLib write-actions for channel/chat operations: react, mute, join, leave.
 *
 * Each method is a thin coroutine wrapper around a single TDLib call wrapped in
 * [warnUnlessCancelled] so the UI can fire-and-forget without crashing on rare TDLib
 * errors (FLOOD_WAIT, restricted reactions, channel left mid-tap…). Repositories that
 * own the resulting state ([PostsRepository] for reactions, this layer for chat info)
 * pick up the change via TDLib's update stream — no need to round-trip the result here.
 *
 * User-initiated actions (react / mute / join / leave) surface failures via
 * [UserMessageBus]; cosmetic reads (channelInfo) stay silent because the sheet shows
 * its own loading/empty state.
 */
class ChannelActionsRepository(
    private val td: TdSender,
    private val userMessages: UserMessageBus,
    private val connection: StateFlow<ConnectionStatus>,
    private val res: StringResolver,
) {

    /**
     * Toggle one of the user's reactions on a message. `isChosen` is the value as the user
     * sees it BEFORE tapping; we flip the side. Passing `isBig=false` and
     * `updateRecentReactions=true` floats the user's most-used reactions to the top of
     * TDLib's reaction picker — same behaviour the official client ships.
     *
     * Custom-emoji reactions go through the same path: TDLib gates them server-side (only
     * Premium users can add custom-emoji reactions to most chats), and on rejection it
     * just no-ops with an error we log and discard. The UI chip still toggles optimistic
     * if the caller wants — TDLib will reconcile via UpdateMessageInteractionInfo.
     */
    /**
     * Toggle the user's reaction and return whether the RPC succeeded. Callers that
     * applied an optimistic local update use the boolean to decide whether to revert.
     * Failures still surface to [UserMessageBus] here — the boolean is purely the
     * rollback signal, not a substitute for user-facing error routing.
     */
    suspend fun toggleReaction(
        chatId: ChatId,
        messageId: MessageId,
        kind: ReactionKind,
        isChosen: Boolean,
    ): Boolean {
        val type = kind.toTd()
        val outcome = runCatching {
            if (isChosen) {
                td.send(TdApi.RemoveMessageReaction(chatId.value, messageId.value, type))
            } else {
                td.send(
                    TdApi.AddMessageReaction(
                        chatId.value,
                        messageId.value,
                        type,
                        /* isBig */ false,
                        /* updateRecentReactions */ true,
                    ),
                )
            }
        }
            .warnUnlessCancelled(TAG, "toggleReaction(${kind.stableKey}, isChosen=$isChosen)")
            .onFailure { it.surfaceTo(userMessages, res, Res.string.op_change_reaction, connection.value) }
        // TDLib code 406 = "silent no-op, do not display, wait for UpdateMessageInteractionInfo".
        // Treat as success so the caller does not revert the optimistic chip flip.
        return outcome.isSuccess || outcome.exceptionOrNull().isTdSilent()
    }

    /**
     * Cast (or retract) a vote on a poll. [optionIds] is the 0-based [PollOption.index]
     * array TDLib expects in [TdApi.SetPollAnswer.optionIds]:
     *   * empty array → retract any existing vote (regular polls with `allowsRevoting`);
     *   * length 1 → single-answer poll, or one of several choices in a multi-answer poll;
     *   * length > 1 → multi-answer poll commit.
     *
     * The UI applies an optimistic local flip via [PostsRepository.applyOptimisticPollAnswer]
     * BEFORE this call; the eventual [TdApi.UpdateMessageContent] from TDLib carries the
     * authoritative new poll state and overwrites our guess via the existing
     * [PostsRepository.handleContentChanged] update path. On RPC failure callers invoke
     * the optimistic helper a second time with the original state to roll back the visible
     * change — same pattern as [toggleReaction].
     *
     * Quiz polls reject retraction server-side; the UI hides the "Retract vote" affordance
     * for quizzes so we never reach this with an empty array for a quiz in practice.
     */
    suspend fun setPollAnswer(
        chatId: ChatId,
        messageId: MessageId,
        optionIds: IntArray,
    ): Boolean {
        val outcome = runCatching {
            td.send(TdApi.SetPollAnswer(chatId.value, messageId.value, optionIds.toTypedArray()))
        }
            .warnUnlessCancelled(TAG, "setPollAnswer(chat=${chatId.value} msg=${messageId.value}, n=${optionIds.size})")
            .onFailure { it.surfaceTo(userMessages, res, Res.string.op_vote_in_poll, connection.value) }
        // TDLib code 406 = silent no-op per the documented `error` contract — typically fires
        // when the local poll mirror already matches the requested selection (the canonical
        // "every tap shows error 406" repro for channel polls). The eventual
        // UpdateMessageContent carries the authoritative percentages; surfacing failure here
        // would (a) flash a snackbar TDLib forbids, (b) trigger clearPollPending(revert=true)
        // and undo the chosen-row chip even though the vote will land.
        return outcome.isSuccess || outcome.exceptionOrNull().isTdSilent()
    }

    /**
     * Mark a poll as closed (chat admin / poll author only). Returns true on success.
     *
     * Hortay UI does not currently surface a "Close poll" button — readers can't author
     * polls in this app — but we keep the suspend wrapper here because anyone admin-listed
     * for the source channel reaches this same client process, and a future "manage your
     * channel" surface would call this method without further wiring.
     */
    suspend fun stopPoll(chatId: ChatId, messageId: MessageId): Boolean {
        val outcome = runCatching {
            td.send(TdApi.StopPoll(chatId.value, messageId.value, /* replyMarkup */ null))
        }
            .warnUnlessCancelled(TAG, "stopPoll(${chatId.value}, ${messageId.value})")
            .onFailure { it.surfaceTo(userMessages, res, Res.string.op_close_poll, connection.value) }
        return outcome.isSuccess
    }

    private fun ReactionKind.toTd(): TdApi.ReactionType = when (this) {
        is ReactionKind.Emoji -> TdApi.ReactionTypeEmoji(text)
        is ReactionKind.CustomEmoji -> TdApi.ReactionTypeCustomEmoji(customEmojiId)
        // Paid reactions require AddPendingPaidMessageReaction (a flow with
        // star-amount confirmation), not AddMessageReaction. We render incoming
        // paid counts but never send our own — passing the same constructor
        // would crash on the server. The toggle call upstream is already
        // gated, so reaching this branch in production would be a bug;
        // surface it as an IllegalStateException for the crash reporter.
        is ReactionKind.Paid -> error("Paid reactions are read-only in this client")
    }

    /**
     * Mute / unmute a chat. `muteFor` is seconds; 0 = unmuted, very large = "forever"
     * which Telegram represents as the special INT_MAX-ish sentinel. We use 365 days
     * as a pragmatic "forever" — matches the official client's longest preset and avoids
     * the user being surprised by reactivated notifications a year out.
     */
    suspend fun setMuted(chatId: ChatId, muted: Boolean) {
        val current = runCatching { td.send(TdApi.GetChat(chatId.value)) }
            .warnUnlessCancelled(TAG, "setMuted/getChat")
            .getOrNull()?.notificationSettings ?: TdApi.ChatNotificationSettings()
        val updated = TdApi.ChatNotificationSettings().apply {
            useDefaultMuteFor = false
            muteFor = if (muted) MUTE_FOREVER_SECONDS else 0
            useDefaultSound = current.useDefaultSound
            soundId = current.soundId
            useDefaultShowPreview = current.useDefaultShowPreview
            showPreview = current.showPreview
            useDefaultMuteStories = current.useDefaultMuteStories
            muteStories = current.muteStories
            useDefaultStorySound = current.useDefaultStorySound
            storySoundId = current.storySoundId
            useDefaultShowStoryPoster = current.useDefaultShowStoryPoster
            showStoryPoster = current.showStoryPoster
            useDefaultDisablePinnedMessageNotifications = current.useDefaultDisablePinnedMessageNotifications
            disablePinnedMessageNotifications = current.disablePinnedMessageNotifications
            useDefaultDisableMentionNotifications = current.useDefaultDisableMentionNotifications
            disableMentionNotifications = current.disableMentionNotifications
        }
        runCatching { td.send(TdApi.SetChatNotificationSettings(chatId.value, updated)) }
            .warnUnlessCancelled(TAG, "setMuted($muted)")
            .onFailure { it.surfaceTo(userMessages, res, if (muted) Res.string.op_mute else Res.string.op_unmute, connection.value) }
    }

    suspend fun isMuted(chatId: ChatId): Boolean {
        val chat = runCatching { td.send(TdApi.GetChat(chatId.value)) }
            .warnUnlessCancelled(TAG, "isMuted")
            .getOrNull() ?: return false
        return chat.notificationSettings?.muteFor.let { it != null && it > 0 }
    }

    suspend fun joinChat(chatId: ChatId) {
        runCatching { td.send(TdApi.JoinChat(chatId.value)) }
            .warnUnlessCancelled(TAG, "joinChat")
            .onFailure { it.surfaceTo(userMessages, res, Res.string.op_join_channel, connection.value) }
    }

    suspend fun leaveChat(chatId: ChatId) {
        runCatching { td.send(TdApi.LeaveChat(chatId.value)) }
            .warnUnlessCancelled(TAG, "leaveChat")
            .onFailure { it.surfaceTo(userMessages, res, Res.string.op_leave_channel, connection.value) }
    }

    /**
     * Resolve a channel info bundle for the bottom sheet: title, handle, description,
     * subscriber count, mute state. Several TDLib calls coalesced — kept on this single
     * suspend method so the UI fires one coroutine and lays out when everything is in.
     */
    suspend fun channelInfo(chatId: ChatId): ChannelInfo? {
        val chat = runCatching { td.send(TdApi.GetChat(chatId.value)) }
            .warnUnlessCancelled(TAG, "channelInfo/getChat")
            .getOrNull() ?: return null
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId
        val supergroup = supergroupId?.let {
            runCatching { td.send(TdApi.GetSupergroup(it)) }
                .warnUnlessCancelled(TAG, "channelInfo/getSupergroup")
                .getOrNull()
        }
        val full = supergroupId?.let {
            runCatching { td.send(TdApi.GetSupergroupFullInfo(it)) }
                .warnUnlessCancelled(TAG, "channelInfo/getSupergroupFullInfo")
                .getOrNull()
        }
        val isMember = supergroup?.status?.let { status ->
            status !is TdApi.ChatMemberStatusLeft && status !is TdApi.ChatMemberStatusBanned
        } ?: false
        return ChannelInfo(
            chatId = chatId,
            title = chat.title.orEmpty(),
            handle = supergroup?.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" },
            description = full?.description?.takeUnless { it.isNullOrBlank() },
            subscribers = supergroup?.memberCount?.takeIf { it > 0 },
            isMuted = chat.notificationSettings?.muteFor.let { it != null && it > 0 },
            isMember = isMember,
        )
    }

    /**
     * Resolve a [UserProfile] bundle for the user-profile bottom sheet. Coalesces
     * `GetUser` + `GetUserFullInfo` + (when set) `GetChat` for the linked personal
     * channel into one suspending call so the UI fires a single coroutine and lays
     * out when everything is in. Mirrors [channelInfo] in shape — the sheet stays
     * visible during the loading window and fields populate inline as TDLib responds.
     *
     * The personal channel resolves to a [PersonalChannelLink]; subscriber count
     * rides along when the supergroup is already in TDLib's cache, otherwise the
     * row just renders without that subtitle line.
     */
    suspend fun userProfile(userId: UserId): UserProfile? {
        val user = runCatching { td.send(TdApi.GetUser(userId.value)) }
            .warnUnlessCancelled(TAG, "userProfile/getUser")
            .getOrNull() ?: return null
        val full = runCatching { td.send(TdApi.GetUserFullInfo(userId.value)) }
            .warnUnlessCancelled(TAG, "userProfile/getUserFullInfo")
            .getOrNull()
        val personal = full?.personalChatId?.takeIf { it != 0L }?.let { personalChatId ->
            val chat = runCatching { td.send(TdApi.GetChat(personalChatId)) }
                .warnUnlessCancelled(TAG, "userProfile/getChat(personal)")
                .getOrNull() ?: return@let null
            val sg = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId?.let { sgId ->
                runCatching { td.send(TdApi.GetSupergroup(sgId)) }
                    .warnUnlessCancelled(TAG, "userProfile/getSupergroup(personal)")
                    .getOrNull()
            }
            PersonalChannelLink(
                chatId = ChatId(personalChatId),
                title = chat.title.orEmpty(),
                handle = sg?.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" },
                avatarThumb = chat.photo?.minithumbnail?.data,
                avatarFileId = chat.photo?.small?.id,
                subscribers = sg?.memberCount?.takeIf { it > 0 },
            )
        }
        val handle = user.usernames?.activeUsernames?.firstOrNull()
        val displayName = listOfNotNull(
            user.firstName?.takeUnless { it.isBlank() },
            user.lastName?.takeUnless { it.isBlank() },
        ).joinToString(" ").ifBlank { handle?.let { "@$it" } ?: "" }
        return UserProfile(
            userId = userId,
            displayName = displayName,
            handle = handle?.let { "@$it" },
            avatarThumb = user.profilePhoto?.minithumbnail?.data,
            avatarFileId = user.profilePhoto?.small?.id,
            verification = user.verificationStatus?.toUserMark(),
            isPremium = user.isPremium,
            emojiStatusId = resolveEmojiStatusId(user.emojiStatus),
            profileAccentColorId = user.profileAccentColorId,
            isBot = user.type is TdApi.UserTypeBot,
            isSupport = user.isSupport,
            isContact = user.isContact,
            status = user.status.toPresence(),
            bio = full?.bio?.text?.takeUnless { it.isNullOrBlank() },
            // TDLib delivers only the day/month for users who hid the year — surface what
            // came back. Year 0 is the documented "hidden" sentinel; we just suppress the
            // year segment of the formatted line when it lands as 0.
            birthMonth = full?.birthdate?.month?.takeIf { it in 1..12 },
            birthDay = full?.birthdate?.day?.takeIf { it in 1..31 },
            birthYear = full?.birthdate?.year?.takeIf { it > 0 },
            groupsInCommon = full?.groupInCommonCount ?: 0,
            personalChannel = personal,
            botDescription = full?.botInfo?.shortDescription?.takeUnless { it.isNullOrBlank() }
                ?: full?.botInfo?.description?.takeUnless { it.isNullOrBlank() },
        )
    }

    /**
     * Preview a chat invite link via TDLib's `CheckChatInviteLink`. Returns a
     * snapshot — title, member count, chat kind — that the UI surfaces in a
     * confirmation dialog before joining. Telegram's own clients call this exact
     * method on every invite tap; we mirror the flow. Returns null on a malformed
     * or expired invite (TDLib answers 4xx) — caller treats as silent miss.
     */
    suspend fun previewChatInvite(inviteLink: String): ChatInvitePreview? {
        val info: TdApi.ChatInviteLinkInfo = runCatching {
            td.send(TdApi.CheckChatInviteLink(inviteLink))
        }
            .warnUnlessCancelled(TAG, "previewChatInvite")
            .getOrNull() ?: return null
        return ChatInvitePreview(
            inviteLink = inviteLink,
            chatId = info.chatId.takeIf { it != 0L }?.let(::ChatId),
            title = info.title.orEmpty(),
            memberCount = info.memberCount,
            kind = when (info.type) {
                is TdApi.InviteLinkChatTypeChannel -> InviteLinkKind.Channel
                else -> InviteLinkKind.Group
            },
        )
    }

    /**
     * Join a chat via its invite link. Returns the resulting chat id on success or
     * null on failure (already a member, banned, expired, FLOOD_WAIT…). The TDLib
     * call propagates a user-facing error through [UserMessageBus] on its own so
     * callers don't need to surface anything beyond the success-side navigation.
     */
    suspend fun joinByInvite(inviteLink: String): Long? {
        val chat: TdApi.Chat = runCatching {
            td.send(TdApi.JoinChatByInviteLink(inviteLink))
        }
            .warnUnlessCancelled(TAG, "joinByInvite")
            .onFailure { it.surfaceTo(userMessages, res, Res.string.op_join_channel, connection.value) }
            .getOrNull() ?: return null
        return chat.id
    }

    private companion object {
        const val TAG = "ChannelActionsRepository"
        // 365 days. TDLib treats very-large positive muteFor as "muted indefinitely"; this
        // matches the official client's "Mute forever" preset.
        const val MUTE_FOREVER_SECONDS = 365 * 24 * 60 * 60
    }
}

// ChatInvitePreview + InviteLinkKind live in commonMain/data/ChatInvitePreview.kt so
// the ChatInvitePreviewDialog + LinkDialogState surfaces can use them from shared UI.

// ChannelInfo + UserProfile + PersonalChannelLink + PresenceStatus live in
// commonMain/data/ChannelInfo.kt so the sheet/profile UI surfaces can use
// them without dragging in this TDLib-bound repository.

private fun TdApi.UserStatus.toPresence(): PresenceStatus = when (this) {
    is TdApi.UserStatusOnline -> PresenceStatus.Online
    is TdApi.UserStatusOffline -> PresenceStatus.Offline(wasOnline.toLong())
    is TdApi.UserStatusRecently -> PresenceStatus.Recently
    is TdApi.UserStatusLastWeek -> PresenceStatus.LastWeek
    is TdApi.UserStatusLastMonth -> PresenceStatus.LastMonth
    else -> PresenceStatus.Empty
}

private fun TdApi.VerificationStatus.toUserMark(): SenderVerification? = when {
    isVerified -> SenderVerification.Verified
    isScam -> SenderVerification.Scam
    isFake -> SenderVerification.Fake
    else -> null
}
