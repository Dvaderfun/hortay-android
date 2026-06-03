package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.posts.PublicHandleResult
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportFlowController
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.report.ReportRepository
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

actual class HortayBackend(
    private val auth: IosTdAuthStateMachine,
    private val countriesRepo: CountryRepository,
    private val channelActions: ChannelActionsRepository,
    private val postsRepo: PostsRepository,
    private val commentsRepo: CommentsRepository,
    actual val folders: FoldersFacade?,
    private val reportRepo: ReportRepository,
    actual val reportDialogs: ReportDialogState,
    actual val reportLogStore: ReportLogStore,
    actual val reportExplainerStore: ReportExplainerStore,
    actual val settingsStore: SettingsStore,
    actual val stats: StatsFacade?,
    private val backendScope: CoroutineScope,
) {
    actual val autoDownload: AutoDownloadFacade? = null
    actual val translations: TranslationsFacade? = null

    // ---- Auth -------------------------------------------------------------
    actual val authStage: StateFlow<AuthStage> get() = auth.authStage
    actual val authError: StateFlow<String?> get() = auth.authError
    actual val countries: StateFlow<List<Country>> get() = countriesRepo.countries
    actual val detectedCountryIso: StateFlow<String?> get() = countriesRepo.detectedIso

    actual fun loadCountries() = countriesRepo.load()
    actual suspend fun submitPhone(phoneE164: String) = auth.submitPhone(phoneE164)
    actual suspend fun submitCode(code: String) = auth.submitCode(code)
    actual suspend fun submitPassword(password: String) = auth.submitPassword(password)
    actual suspend fun resendCode() = auth.resendCode()
    actual suspend fun cancelAuth() = auth.cancelAuth()
    actual suspend fun requestPasswordRecovery() = auth.requestPasswordRecovery()
    actual suspend fun recoverPassword(code: String) = auth.recoverPassword(code)
    actual fun clearAuthError() = auth.clearAuthError()

    // ---- Channels / users -------------------------------------------------
    actual suspend fun channelInfo(chatId: ChatId): ChannelInfo? =
        channelActions.channelInfo(chatId)
    actual suspend fun setMuted(chatId: ChatId, muted: Boolean) =
        channelActions.setMuted(chatId, muted)
    actual suspend fun joinChat(chatId: ChatId) = channelActions.joinChat(chatId)
    actual suspend fun leaveChat(chatId: ChatId) = channelActions.leaveChat(chatId)
    actual suspend fun joinByInvite(inviteLink: String): Long? = channelActions.joinByInvite(inviteLink)
    actual suspend fun userProfile(userId: UserId): UserProfile? =
        channelActions.userProfile(userId)

    // ---- Links (no TelegramLinkResolver on iOS yet) -----------------------
    actual suspend fun resolveLink(uri: String): DeepLink? = null

    // ---- Feed -------------------------------------------------------------
    actual val feedPosts: StateFlow<PersistentList<TimelinePost>> get() = postsRepo.posts
    actual val newArrivals: SharedFlow<TimelinePost> get() = postsRepo.newArrivals
    actual val archivedChatIds: StateFlow<Set<Long>> get() = postsRepo.archivedChatIds

    actual suspend fun refreshFeed() = postsRepo.refresh()
    actual suspend fun loadOlder(chatId: ChatId): Int = postsRepo.loadOlder(chatId)
    actual suspend fun loadHistoryAround(chatId: ChatId, anchorMessageId: MessageId): Boolean =
        postsRepo.loadHistoryAround(chatId, anchorMessageId)
    actual suspend fun openChat(chatId: ChatId) = postsRepo.openChat(chatId)
    actual suspend fun closeChat(chatId: ChatId) = postsRepo.closeChat(chatId)
    actual suspend fun viewMessages(chatId: ChatId, messageIds: List<MessageId>) =
        postsRepo.viewMessages(chatId, messageIds)
    actual suspend fun loadChannelHistory(chatId: ChatId): Result<Unit> =
        postsRepo.loadChannelHistory(chatId)
    actual fun hasWarmChannelHistory(chatId: ChatId): Boolean =
        postsRepo.hasWarmChannelHistory(chatId)

    actual suspend fun chatTitle(chatId: ChatId): String? = postsRepo.chatTitle(chatId)
    actual suspend fun channelSubscribers(chatId: ChatId): Int? =
        postsRepo.channelSubscribers(chatId)
    actual fun channelSubscribersCached(chatId: ChatId): Int? =
        postsRepo.channelSubscribersCached(chatId)
    actual suspend fun chatAvatar(chatId: ChatId): Pair<Int?, ByteArray?>? =
        postsRepo.chatAvatar(chatId)

    actual suspend fun searchInChannel(chatId: ChatId, query: String): List<TimelinePost> =
        postsRepo.searchInChannel(chatId, query)

    // ---- Reactions / polls ------------------------------------------------
    actual fun applyOptimisticReaction(
        chatId: ChatId, messageId: MessageId, kind: ReactionKind, nowChosen: Boolean,
    ) = postsRepo.applyOptimisticReaction(chatId, messageId, kind, nowChosen)

    actual fun applyOptimisticPollAnswer(chatId: ChatId, messageId: MessageId, chosenIndices: IntArray) =
        postsRepo.applyOptimisticPollAnswer(chatId, messageId, chosenIndices)

    actual fun clearPollPending(chatId: ChatId, messageId: MessageId, revert: Boolean) =
        postsRepo.clearPollPending(chatId, messageId, revert)

    actual suspend fun toggleReaction(
        chatId: ChatId, messageId: MessageId, kind: ReactionKind, isChosen: Boolean,
    ): Boolean = channelActions.toggleReaction(chatId, messageId, kind, isChosen)

    actual suspend fun setPollAnswer(chatId: ChatId, messageId: MessageId, optionIds: IntArray): Boolean =
        channelActions.setPollAnswer(chatId, messageId, optionIds)

    // ---- Public handle / chat kind ----------------------------------------
    actual suspend fun resolvePublicHandle(handle: String): PublicHandleResult =
        postsRepo.resolvePublicHandle(handle)
    actual suspend fun resolveChatKind(chatId: ChatId): PublicHandleResult =
        postsRepo.resolveChatKind(chatId)

    // ---- Comments ---------------------------------------------------------
    actual fun observeThread(chatId: ChatId, candidateMessageIds: List<MessageId>): Flow<ThreadState> =
        commentsRepo.observeThread(chatId, candidateMessageIds)
    actual suspend fun viewThreadMessages(threadChatId: ChatId, messageIds: List<MessageId>) =
        commentsRepo.viewMessages(threadChatId, messageIds)
    actual suspend fun prefetchThread(chatId: ChatId, candidateMessageIds: List<MessageId>) =
        commentsRepo.prefetchThread(chatId, candidateMessageIds)
    actual fun applyCommentOptimisticReaction(
        threadChatId: ChatId, messageId: MessageId, current: Reactions, kind: ReactionKind, nowChosen: Boolean,
    ) = commentsRepo.applyOptimisticReaction(threadChatId, messageId, current, kind, nowChosen)
    actual fun clearCommentOptimisticReaction(threadChatId: ChatId, messageId: MessageId) =
        commentsRepo.clearOptimisticReaction(threadChatId, messageId)

    actual suspend fun canonicalShareUrl(post: TimelinePost): String? =
        postsRepo.canonicalShareUrl(post)

    // ---- Session ----------------------------------------------------------
    actual val isAuthenticated: StateFlow<Boolean> = auth.authStage
        .map { it == AuthStage.Ready }
        .stateIn(backendScope, SharingStarted.Eagerly, auth.authStage.value == AuthStage.Ready)

    actual suspend fun logOut() = auth.logOut()
    actual val connection: StateFlow<ConnectionStatus> get() = auth.connection
    actual val floodWaitUntilMs: StateFlow<Long> get() = auth.floodWaitUntilMs

    actual suspend fun previewChatInvite(inviteLink: String): ChatInvitePreview? =
        channelActions.previewChatInvite(inviteLink)
    actual fun primeCommentsForOpen(post: TimelinePost) = commentsRepo.primeCommentsForOpen(post)

    actual val reportController: ReportFlowController get() = reportRepo
}
