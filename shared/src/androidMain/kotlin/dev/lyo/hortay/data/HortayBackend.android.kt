package dev.lyo.hortay.data

import kotlinx.coroutines.flow.StateFlow

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
}
