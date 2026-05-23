package dev.lyo.hortay.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS actual for [HortayBackend]. Guest-mode-only stub — every method either
 * returns an empty flow or no-ops. Phase I will replace this with a real impl
 * once TDLib is cross-compiled for Apple platforms (cinterop bindings for the
 * ~200 `TdApi.*` types + native libtdjni.xcframework).
 *
 * Until then the iOS app only ever surfaces guest-mode UI ([WebModeScaffold]),
 * which doesn't touch any of these methods. The stub exists purely so screens
 * that take a `HortayBackend` compile on both targets.
 */
actual class HortayBackend {
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
}
