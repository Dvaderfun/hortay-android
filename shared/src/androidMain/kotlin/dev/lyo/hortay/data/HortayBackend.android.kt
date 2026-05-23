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

/**
 * Android actual for [HortayBackend]. Pure delegation onto the concrete repos
 * `AppGraph` already wires up — no logic of its own. Lets commonMain UI take a
 * single `HortayBackend` reference instead of poking into TDLib-typed
 * repositories.
 */
actual class HortayBackend(
    private val client: TdClient,
    private val countriesRepo: CountryRepository,
    private val channelActions: ChannelActionsRepository,
    private val linkResolver: TelegramLinkResolver,
    private val postsRepo: PostsRepository,
    private val commentsRepo: CommentsRepository,
    actual val folders: FoldersFacade?,
    private val reportRepo: ReportRepository,
    actual val reportDialogs: ReportDialogState,
    actual val reportLogStore: ReportLogStore,
    actual val reportExplainerStore: ReportExplainerStore,
    actual val settingsStore: SettingsStore,
    actual val stats: StatsFacade?,
    actual val autoDownload: AutoDownloadFacade?,
    private val translationsStore: TranslationsStore,
    private val backendScope: CoroutineScope,
) {
    actual val authStage: StateFlow<AuthStage> get() = client.authStage
    actual val authError: StateFlow<String?> get() = client.authError
    actual val countries: StateFlow<List<Country>> get() = countriesRepo.countries
    actual val detectedCountryIso: StateFlow<String?> get() = countriesRepo.detectedIso

    actual fun loadCountries() = countriesRepo.load()
    actual suspend fun submitPhone(phoneE164: String) = client.submitPhone(phoneE164)
    actual suspend fun submitCode(code: String) = client.submitCode(code)
    actual suspend fun submitPassword(password: String) = client.submitPassword(password)
    actual suspend fun resendCode() = client.resendCode()
    actual suspend fun cancelAuth() = client.cancelAuth()
    actual suspend fun requestPasswordRecovery() = client.requestPasswordRecovery()
    actual suspend fun recoverPassword(code: String) = client.recoverPassword(code)
    actual fun clearAuthError() = client.clearAuthError()

    actual suspend fun channelInfo(chatId: Long): ChannelInfo? =
        channelActions.channelInfo(chatId)
    actual suspend fun setMuted(chatId: Long, muted: Boolean) =
        channelActions.setMuted(chatId, muted)
    actual suspend fun joinChat(chatId: Long) = channelActions.joinChat(chatId)
    actual suspend fun leaveChat(chatId: Long) = channelActions.leaveChat(chatId)
    actual suspend fun joinByInvite(inviteLink: String): Long? = channelActions.joinByInvite(inviteLink)
    actual suspend fun userProfile(userId: Long): UserProfile? =
        channelActions.userProfile(userId)

    actual suspend fun resolveLink(uri: String): DeepLink? {
        val parsed = runCatching { android.net.Uri.parse(uri) }.getOrNull() ?: return null
        return linkResolver.resolve(parsed)
    }

    actual val feedPosts: StateFlow<PersistentList<TimelinePost>> get() = postsRepo.posts
    actual val newArrivals: SharedFlow<TimelinePost> get() = postsRepo.newArrivals
    actual val archivedChatIds: StateFlow<Set<Long>> get() = postsRepo.archivedChatIds

    actual suspend fun refreshFeed() = postsRepo.refresh()
    actual suspend fun loadOlder(chatId: Long): Int = postsRepo.loadOlder(chatId)
    actual suspend fun loadHistoryAround(chatId: Long, anchorMessageId: Long): Boolean =
        postsRepo.loadHistoryAround(chatId, anchorMessageId)
    actual suspend fun openChat(chatId: Long) = postsRepo.openChat(chatId)
    actual suspend fun closeChat(chatId: Long) = postsRepo.closeChat(chatId)
    actual suspend fun viewMessages(chatId: Long, messageIds: List<Long>) =
        postsRepo.viewMessages(chatId, messageIds)
    actual suspend fun loadChannelHistory(chatId: Long): Result<Unit> =
        postsRepo.loadChannelHistory(chatId)
    actual fun hasWarmChannelHistory(chatId: Long): Boolean =
        postsRepo.hasWarmChannelHistory(chatId)

    actual suspend fun chatTitle(chatId: Long): String? = postsRepo.chatTitle(chatId)
    actual suspend fun channelSubscribers(chatId: Long): Int? =
        postsRepo.channelSubscribers(chatId)
    actual fun channelSubscribersCached(chatId: Long): Int? =
        postsRepo.channelSubscribersCached(chatId)
    actual suspend fun chatAvatar(chatId: Long): Pair<Int?, ByteArray?>? =
        postsRepo.chatAvatar(chatId)

    actual suspend fun searchInChannel(chatId: Long, query: String): List<TimelinePost> =
        postsRepo.searchInChannel(chatId, query)

    actual fun applyOptimisticReaction(
        chatId: Long,
        messageId: Long,
        kind: ReactionKind,
        nowChosen: Boolean,
    ) = postsRepo.applyOptimisticReaction(chatId, messageId, kind, nowChosen)

    actual fun applyOptimisticPollAnswer(chatId: Long, messageId: Long, chosenIndices: IntArray) =
        postsRepo.applyOptimisticPollAnswer(chatId, messageId, chosenIndices)

    actual fun clearPollPending(chatId: Long, messageId: Long, revert: Boolean) =
        postsRepo.clearPollPending(chatId, messageId, revert)

    actual suspend fun toggleReaction(
        chatId: Long,
        messageId: Long,
        kind: ReactionKind,
        isChosen: Boolean,
    ): Boolean = channelActions.toggleReaction(chatId, messageId, kind, isChosen)

    actual suspend fun setPollAnswer(chatId: Long, messageId: Long, optionIds: IntArray): Boolean =
        channelActions.setPollAnswer(chatId, messageId, optionIds)

    actual suspend fun resolvePublicHandle(handle: String): PublicHandleResult =
        postsRepo.resolvePublicHandle(handle)

    actual suspend fun resolveChatKind(chatId: Long): PublicHandleResult =
        postsRepo.resolveChatKind(chatId)

    actual fun observeThread(chatId: Long, candidateMessageIds: List<Long>): Flow<ThreadState> =
        commentsRepo.observeThread(chatId, candidateMessageIds)

    actual suspend fun viewThreadMessages(threadChatId: Long, messageIds: List<Long>) {
        commentsRepo.viewMessages(threadChatId, messageIds)
    }

    actual suspend fun prefetchThread(chatId: Long, candidateMessageIds: List<Long>) =
        commentsRepo.prefetchThread(chatId, candidateMessageIds)

    actual fun applyCommentOptimisticReaction(
        threadChatId: Long,
        messageId: Long,
        current: Reactions,
        kind: ReactionKind,
        nowChosen: Boolean,
    ) = commentsRepo.applyOptimisticReaction(threadChatId, messageId, current, kind, nowChosen)

    actual fun clearCommentOptimisticReaction(threadChatId: Long, messageId: Long) =
        commentsRepo.clearOptimisticReaction(threadChatId, messageId)

    actual suspend fun canonicalShareUrl(post: TimelinePost): String? =
        postsRepo.canonicalShareUrl(post)

    actual val isAuthenticated: StateFlow<Boolean> = client.authStage
        .map { it == AuthStage.Ready }
        .stateIn(backendScope, SharingStarted.Eagerly, client.authStage.value == AuthStage.Ready)

    actual suspend fun logOut() = client.logOut()

    actual val reportController: ReportFlowController get() = reportRepo

    actual val translations: TranslationsFacade? get() = translationsStore
}
