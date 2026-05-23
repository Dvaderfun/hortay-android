package dev.lyo.hortay.data

import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportFlowController
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.report.ReportOption
import dev.lyo.hortay.data.report.ReportState
import dev.lyo.hortay.data.report.ReportStep
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * iOS actual for [HortayBackend]. Guest-mode-only stub — every method either
 * returns an empty flow or no-ops. Phase II will replace this with a real impl
 * once TDLib is cross-compiled for Apple platforms (cinterop bindings for the
 * ~200 `TdApi.*` types + native libtdjni.xcframework).
 *
 * Until then the iOS app only ever surfaces guest-mode UI ([WebModeScaffold]),
 * which doesn't touch any of these methods. The stub exists purely so screens
 * that take a `HortayBackend` compile on both targets.
 */
actual class HortayBackend(
    actual val settingsStore: SettingsStore,
    actual val reportLogStore: ReportLogStore,
    actual val reportExplainerStore: ReportExplainerStore,
) {
    actual val authStage: StateFlow<AuthStage> = MutableStateFlow<AuthStage>(AuthStage.WaitPhone).asStateFlow()
    actual val authError: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    actual val countries: StateFlow<List<Country>> = MutableStateFlow<List<Country>>(emptyList()).asStateFlow()
    actual val detectedCountryIso: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    actual fun loadCountries() {}
    actual suspend fun submitPhone(phoneE164: String) {}
    actual suspend fun submitCode(code: String) {}
    actual suspend fun submitPassword(password: String) {}
    actual suspend fun resendCode() {}
    actual suspend fun cancelAuth() {}
    actual suspend fun requestPasswordRecovery() {}
    actual suspend fun recoverPassword(code: String) {}
    actual fun clearAuthError() {}

    actual suspend fun channelInfo(chatId: Long): ChannelInfo? = null
    actual suspend fun setMuted(chatId: Long, muted: Boolean) {}
    actual suspend fun joinChat(chatId: Long) {}
    actual suspend fun leaveChat(chatId: Long) {}
    actual suspend fun userProfile(userId: Long): UserProfile? = null
    actual suspend fun resolveLink(uri: String): DeepLink? = null

    actual val feedPosts: StateFlow<PersistentList<TimelinePost>> =
        MutableStateFlow(persistentListOf<TimelinePost>()).asStateFlow()

    actual fun observeThread(chatId: Long, candidateMessageIds: List<Long>): Flow<ThreadState> =
        flowOf(ThreadState.Loading)

    actual suspend fun viewThreadMessages(threadChatId: Long, messageIds: List<Long>) {}
    actual suspend fun canonicalShareUrl(post: TimelinePost): String? = null

    actual val isAuthenticated: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    actual suspend fun logOut() {}

    actual val reportController: ReportFlowController = NoopReportController
    actual val reportDialogs: ReportDialogState = ReportDialogState()
    actual val translations: TranslationsFacade? = null
}

/** No-op stub so guest-mode iOS compiles. Never invoked in practice. */
private object NoopReportController : ReportFlowController {
    private val noResultStep = ReportStep(ReportState.Error("not supported"))
    override suspend fun start(chatId: Long, messageId: Long?): ReportStep = noResultStep
    override suspend fun selectOption(chatId: Long, messageId: Long?, option: ReportOption): ReportStep = noResultStep
    override suspend fun submitText(chatId: Long, messageId: Long?, optionId: ByteArray, text: String): ReportStep = noResultStep
}
