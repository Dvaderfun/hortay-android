@file:OptIn(ExperimentalForeignApi::class)

package dev.lyo.hortay.data

import dev.lyo.hortay.AppConfig
import dev.lyo.hortay.tdlib.TdApi
import dev.lyo.hortay.tdlib.TypedTdClient
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.auth_channel_call
import hortay.shared.generated.resources.auth_channel_call_with_phone
import hortay.shared.generated.resources.auth_channel_firebase
import hortay.shared.generated.resources.auth_channel_flash_call
import hortay.shared.generated.resources.auth_channel_fragment
import hortay.shared.generated.resources.auth_channel_missed_call
import hortay.shared.generated.resources.auth_channel_other
import hortay.shared.generated.resources.auth_channel_sms
import hortay.shared.generated.resources.auth_channel_sms_phrase
import hortay.shared.generated.resources.auth_channel_sms_with_phone
import hortay.shared.generated.resources.auth_channel_sms_word
import hortay.shared.generated.resources.auth_channel_telegram_message
import hortay.shared.generated.resources.auth_err_email_required
import hortay.shared.generated.resources.auth_err_init_failure
import hortay.shared.generated.resources.auth_err_other_device
import hortay.shared.generated.resources.auth_err_phone_unregistered
import hortay.shared.generated.resources.auth_err_premium_required
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.UIKit.UIDevice

/**
 * iOS twin of Android's [TdClient.kt]'s `onAuthState` switch + auth submit
 * methods. Owns the authorisation state machine, the friendly-error pipeline,
 * the connection mirror, and the FLOOD_WAIT countdown. Delegates raw I/O to
 * the injected [TypedTdClient] (which wraps cinterop libtdjson).
 *
 * Phase II-D2 scope: every auth state Android handles is mirrored here.
 * Phase II-D1 will lift this whole class to commonMain once `TdSender` lands
 * as a common interface and the Android side adapts — until then Android's
 * `TdClient` and this class stay parallel implementations of the same
 * contract.
 *
 * **Update fan-out.** TypedTdClient already coalesces tdjson's raw JSON into
 * a typed `SharedFlow<TdApi.Object>`. We collect from it on a single
 * coroutine launched on [scope] and dispatch authorisation / connection /
 * (future-phase) update events. The collector runs on Dispatchers.Default;
 * inside it we update `MutableStateFlow.value` synchronously (non-suspending,
 * thread-safe) and only `launch { sendSetTdlibParameters() }` for the few
 * states that need an outbound RPC.
 *
 * **No `start()` separate from `init {}`.** Unlike Android (where `TdClient`
 * defers `Client.create` because TDLib's JNI startup is heavy), the K/N
 * tdjson path is light enough that creating the cinterop client up front is
 * fine. Koin's `createdAtStart = true` on this binding guarantees the
 * collector subscribes before any caller touches `authStage`.
 */
class IosTdAuthStateMachine(
    val client: TypedTdClient,
    private val scope: CoroutineScope,
    private val res: StringResolver,
) {

    private val _authStage = MutableStateFlow<AuthStage>(AuthStage.Loading)
    val authStage: StateFlow<AuthStage> = _authStage.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    /**
     * Fires when TDLib emits [TdApi.AuthorizationStateLoggingOut]. Subscribers
     * — wired through Koin's `LogoutCleanup` once Phase II-D1 lifts that path
     * to commonMain — wipe per-account caches before the next `Closed` →
     * respawn cycle creates a fresh session.
     */
    private val _loggedOut = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val loggedOut: SharedFlow<Unit> = _loggedOut.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionStatus.Connecting)
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    /**
     * Active FLOOD_WAIT deadline (epoch ms). Currently emits 0 — the iOS path
     * doesn't yet share Android's global gate (Phase II-D1 lifts that). Auth
     * submits still surface a friendly "Too many attempts" string through
     * [authError]; the deadline-aware backoff arrives with the repository lift.
     */
    private val _floodWaitUntilMs = MutableStateFlow(0L)
    val floodWaitUntilMs: StateFlow<Long> = _floodWaitUntilMs.asStateFlow()

    // Last phone the user attempted, kept so the WaitCode stage can render the
    // destination even when TDLib doesn't echo it back in `codeInfo`.
    private var lastAttemptedPhone: String = ""

    init {
        scope.launch(Dispatchers.Default) {
            client.updates.collect { obj ->
                when (obj) {
                    is TdApi.UpdateAuthorizationState -> obj.authorizationState?.let { onAuthState(it) }
                    is TdApi.UpdateConnectionState -> obj.state?.let { _connection.value = it.toStatus() }
                    else -> Unit
                }
            }
        }
    }

    private fun TdApi.ConnectionState.toStatus(): ConnectionStatus = when (this) {
        is TdApi.ConnectionStateReady -> ConnectionStatus.Ready
        is TdApi.ConnectionStateConnecting -> ConnectionStatus.Connecting
        is TdApi.ConnectionStateConnectingToProxy -> ConnectionStatus.Connecting
        is TdApi.ConnectionStateUpdating -> ConnectionStatus.Updating
        is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WaitingForNetwork
        else -> ConnectionStatus.Connecting
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters ->
                scope.launch(Dispatchers.Default) { sendSetTdlibParameters() }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authStage.value = AuthStage.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> {
                val info = state.codeInfo
                val phone = info?.phoneNumber.orEmpty().ifBlank { lastAttemptedPhone }
                _authStage.value = AuthStage.WaitCode(
                    phoneNumber = phone,
                    codeLength = info?.type?.numericLength() ?: DEFAULT_CODE_LENGTH,
                    channelLabel = info?.type?.toLabel(res, phone)
                        ?: res.getString(Res.string.auth_channel_other),
                    nextChannelLabel = info?.nextType?.toLabel(res, phone),
                    resendAvailableInSec = info?.timeout ?: 0,
                    isNumeric = info?.type?.isNumeric() != false,
                )
            }
            is TdApi.AuthorizationStateWaitPassword -> _authStage.value = AuthStage.WaitPassword(
                hint = state.passwordHint,
                hasRecoveryEmail = state.hasRecoveryEmailAddress,
                recoveryEmailPattern = state.recoveryEmailAddressPattern,
            )
            is TdApi.AuthorizationStateReady -> _authStage.value = AuthStage.Ready
            is TdApi.AuthorizationStateLoggingOut -> {
                _authStage.value = AuthStage.Loading
                _loggedOut.tryEmit(Unit)
            }
            is TdApi.AuthorizationStateClosing -> _authStage.value = AuthStage.Loading
            is TdApi.AuthorizationStateClosed -> {
                _authStage.value = AuthStage.Loading
                lastAttemptedPhone = ""
                // The K/N tdjson client doesn't surface a `respawn` hook the
                // way Android's Client.create does — TDLib itself spawns a
                // fresh client id internally when the previous one Closes, so
                // we just clear our local UI state and wait for the next
                // WaitTdlibParameters update on the existing TypedTdClient.
            }
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode ->
                _authStage.value = AuthStage.Error(res.getString(Res.string.auth_err_email_required))
            is TdApi.AuthorizationStateWaitRegistration ->
                _authStage.value = AuthStage.Error(res.getString(Res.string.auth_err_phone_unregistered))
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                _authStage.value = AuthStage.Error(res.getString(Res.string.auth_err_other_device))
            is TdApi.AuthorizationStateWaitPremiumPurchase ->
                _authStage.value = AuthStage.Error(res.getString(Res.string.auth_err_premium_required))
        }
    }

    private suspend fun sendSetTdlibParameters() {
        val params = TdApi.SetTdlibParameters(
            useTestDc = false,
            databaseDirectory = applicationDirectory(NSDocumentDirectory, "tdlib"),
            filesDirectory = applicationDirectory(NSCachesDirectory, "tdlib-files"),
            useFileDatabase = true,
            useChatInfoDatabase = true,
            useMessageDatabase = true,
            useSecretChats = false,
            apiId = AppConfig.telegramApiId,
            apiHash = AppConfig.telegramApiHash,
            systemLanguageCode = NSLocale.currentLocale.languageCode.ifBlank { "en" },
            deviceModel = UIDevice.currentDevice.model,
            systemVersion = "iOS ${UIDevice.currentDevice.systemVersion}",
            applicationVersion = appVersion(),
        )
        runCatching { client.send(params) }
            .onFailure { err ->
                if (err is CancellationException) throw err
                _authStage.value = AuthStage.Error(res.getString(Res.string.auth_err_init_failure))
            }
    }

    fun clearAuthError() {
        _authError.value = null
    }

    suspend fun submitPhone(phone: String) {
        lastAttemptedPhone = phone
        _authError.value = null
        runCatching { client.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber = phone)) }
            .reportAuthFailure()
    }

    suspend fun submitCode(code: String) {
        _authError.value = null
        runCatching { client.send(TdApi.CheckAuthenticationCode(code = code)) }
            .reportAuthFailure()
    }

    suspend fun submitPassword(password: String) {
        _authError.value = null
        runCatching { client.send(TdApi.CheckAuthenticationPassword(password = password)) }
            .reportAuthFailure()
    }

    suspend fun requestPasswordRecovery() {
        _authError.value = null
        runCatching { client.send(TdApi.RequestAuthenticationPasswordRecovery()) }
            .reportAuthFailure()
    }

    suspend fun recoverPassword(recoveryCode: String) {
        _authError.value = null
        runCatching {
            client.send(
                TdApi.RecoverAuthenticationPassword(
                    recoveryCode = recoveryCode,
                    newPassword = "",
                    newHint = "",
                ),
            )
        }.reportAuthFailure()
    }

    suspend fun resendCode() {
        _authError.value = null
        runCatching { client.send(TdApi.ResendAuthenticationCode()) }
            .reportAuthFailure()
    }

    suspend fun cancelAuth() {
        _authError.value = null
        when (_authStage.value) {
            is AuthStage.WaitCode, is AuthStage.WaitPassword -> {
                _authStage.value = AuthStage.WaitPhone
                lastAttemptedPhone = ""
            }
            else -> runCatching { client.send(TdApi.LogOut()) }
        }
    }

    suspend fun logOut() {
        runCatching { client.send(TdApi.LogOut()) }
    }

    /**
     * Cancellations from a recompose-killed `rememberCoroutineScope` are normal
     * lifecycle events, not errors. Real failures get translated by the common
     * [friendlyAuthErrorMessage] adapter and routed to [_authError] so the
     * current form stays mounted with an inline error message.
     */
    private fun Result<*>.reportAuthFailure() {
        onFailure { err ->
            if (err is CancellationException) return@onFailure
            _authError.value = friendlyAuthErrorMessage(res, err)
        }
    }

    companion object {
        private const val DEFAULT_CODE_LENGTH = 5

        @OptIn(ExperimentalForeignApi::class)
        private fun applicationDirectory(searchDir: ULong, name: String): String {
            val url: NSURL? = NSFileManager.defaultManager.URLForDirectory(
                directory = searchDir,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )
            val root = url?.path
                ?: error("Cannot resolve iOS base directory ($searchDir) for $name")
            val full = "$root/$name"
            // TDLib creates the directory itself if missing, but doing so up
            // front lets us surface a clearer error if the sandbox forbids it.
            NSFileManager.defaultManager.createDirectoryAtPath(
                path = full,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            return full
        }

        private fun appVersion(): String {
            val info = NSBundle.mainBundle.infoDictionary ?: return AppConfig.versionName
            return (info["CFBundleShortVersionString"] as? String) ?: AppConfig.versionName
        }
    }
}

/**
 * Digit count expected by [this] code channel, or null when the channel does not deliver
 * a numeric code (SmsWord, SmsPhrase, FlashCall — those expect a word/phrase or pattern).
 */
private fun TdApi.AuthenticationCodeType.numericLength(): Int? = when (this) {
    is TdApi.AuthenticationCodeTypeTelegramMessage -> length
    is TdApi.AuthenticationCodeTypeSms -> length
    is TdApi.AuthenticationCodeTypeCall -> length
    is TdApi.AuthenticationCodeTypeMissedCall -> length
    is TdApi.AuthenticationCodeTypeFragment -> length
    is TdApi.AuthenticationCodeTypeFirebaseAndroid -> length
    is TdApi.AuthenticationCodeTypeFirebaseIos -> length
    else -> null
}

private fun TdApi.AuthenticationCodeType.isNumeric(): Boolean = numericLength() != null

/**
 * Pre-render a localised description of the code-delivery channel so the UI doesn't have
 * to switch on TDLib's [TdApi.AuthenticationCodeType] sum type. Mirrors Android's
 * sibling — same `Res.string.auth_channel_*` keys. The two implementations stay
 * parallel until Phase II-D1 lifts the whole repository layer to commonMain and
 * collapses both onto a shared intermediate enum.
 */
private fun TdApi.AuthenticationCodeType.toLabel(res: StringResolver, phone: String): String = when (this) {
    is TdApi.AuthenticationCodeTypeTelegramMessage -> res.getString(Res.string.auth_channel_telegram_message)
    is TdApi.AuthenticationCodeTypeSms ->
        if (phone.isNotEmpty()) res.getString(Res.string.auth_channel_sms_with_phone, phone)
        else res.getString(Res.string.auth_channel_sms)
    is TdApi.AuthenticationCodeTypeSmsWord -> res.getString(Res.string.auth_channel_sms_word)
    is TdApi.AuthenticationCodeTypeSmsPhrase -> res.getString(Res.string.auth_channel_sms_phrase)
    is TdApi.AuthenticationCodeTypeCall ->
        if (phone.isNotEmpty()) res.getString(Res.string.auth_channel_call_with_phone, phone)
        else res.getString(Res.string.auth_channel_call)
    is TdApi.AuthenticationCodeTypeMissedCall -> res.getString(Res.string.auth_channel_missed_call)
    is TdApi.AuthenticationCodeTypeFlashCall -> res.getString(Res.string.auth_channel_flash_call)
    is TdApi.AuthenticationCodeTypeFragment -> res.getString(Res.string.auth_channel_fragment)
    is TdApi.AuthenticationCodeTypeFirebaseAndroid,
    is TdApi.AuthenticationCodeTypeFirebaseIos -> res.getString(Res.string.auth_channel_firebase)
    else -> res.getString(Res.string.auth_channel_other)
}
