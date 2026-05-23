package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportFlowController
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.report.ReportRepository
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
    private val reportRepo: ReportRepository,
    actual val reportDialogs: ReportDialogState,
    actual val reportLogStore: ReportLogStore,
    actual val reportExplainerStore: ReportExplainerStore,
    actual val settingsStore: SettingsStore,
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
    actual suspend fun userProfile(userId: Long): UserProfile? =
        channelActions.userProfile(userId)

    actual suspend fun resolveLink(uri: String): DeepLink? {
        val parsed = runCatching { android.net.Uri.parse(uri) }.getOrNull() ?: return null
        return linkResolver.resolve(parsed)
    }

    actual val feedPosts: StateFlow<PersistentList<TimelinePost>> get() = postsRepo.posts

    actual fun observeThread(chatId: Long, candidateMessageIds: List<Long>): Flow<ThreadState> =
        commentsRepo.observeThread(chatId, candidateMessageIds)

    actual suspend fun viewThreadMessages(threadChatId: Long, messageIds: List<Long>) {
        commentsRepo.viewMessages(threadChatId, messageIds)
    }

    actual suspend fun canonicalShareUrl(post: TimelinePost): String? =
        postsRepo.canonicalShareUrl(post)

    actual val isAuthenticated: StateFlow<Boolean> = client.authStage
        .map { it == AuthStage.Ready }
        .stateIn(backendScope, SharingStarted.Eagerly, client.authStage.value == AuthStage.Ready)

    actual suspend fun logOut() = client.logOut()

    actual val reportController: ReportFlowController get() = reportRepo

    actual val translations: TranslationsFacade? get() = translationsStore
}
