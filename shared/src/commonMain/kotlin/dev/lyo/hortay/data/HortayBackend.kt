package dev.lyo.hortay.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic surface of the authenticated Telegram backend. Wraps
 * TDLib on Android (delegates to `TdClient` + the surrounding repositories
 * inside `AppGraph`). On iOS this returns stubs / empty flows because TDLib
 * isn't ported there yet — guest-mode-only UI is unaffected.
 *
 * **Why this exists.** TDLib types (`org.drinkless.tdlib.TdApi.*`) are Java
 * source generated from the upstream `.tl` schema and are Android-only. Any
 * Compose screen that touched TDLib directly was stuck in `androidMain`. The
 * abstraction takes only commonMain value types in / out, so screens can move
 * to `commonMain` and compile on both targets — iOS guest mode keeps working
 * because the stub never crashes, just returns no-ops for the methods the
 * authenticated UI invokes.
 *
 * Grows phase by phase (H3) as each screen moves over. Stays a single
 * `expect class` rather than a per-screen interface set so consumers can
 * keep a single backend reference instead of juggling N coupled handles.
 */
expect class HortayBackend {
    // ---- Auth ----------------------------------------------------------

    /** Where TDLib's authorisation state machine currently is. */
    val authStage: StateFlow<AuthStage>

    /**
     * Friendly error message from the most recent auth submit, or null when
     * none is pending. Kept separate from [authStage] so a rejected code
     * doesn't blow the user back to a blank screen — they stay on the same
     * form with their input intact and an inline message under the field.
     */
    val authError: StateFlow<String?>

    /**
     * Catalogue of dial-code countries, localised to the user's language.
     * Empty until [loadCountries] resolves; surface a loading state in the
     * picker rather than gating the form on it.
     */
    val countries: StateFlow<List<Country>>

    /**
     * Best-effort carrier-detected ISO for the initial picker selection.
     * Null until the platform reports it; consumers fall back to a sensible
     * default (UA for this app).
     */
    val detectedCountryIso: StateFlow<String?>

    /**
     * Lazily fetch the country catalogue + carrier ISO. Idempotent — call
     * from each phone-form mount; subsequent calls are no-ops once loaded.
     */
    fun loadCountries()

    /**
     * Submit a fully-qualified E.164 number to TDLib. Result lands as the
     * next [authStage] emission (typically `WaitCode`); errors land in
     * [authError].
     */
    suspend fun submitPhone(phoneE164: String)

    /** Submit the verification code the user typed. */
    suspend fun submitCode(code: String)

    /** Submit the 2FA password when [authStage] is `WaitPassword`. */
    suspend fun submitPassword(password: String)

    /** Ask TDLib to resend the verification code via the next channel. */
    suspend fun resendCode()

    /**
     * Roll the auth state back one step. From WaitCode/WaitPassword goes to
     * WaitPhone so the user can re-enter their number; from a recoverable
     * error pushes back to the entry point.
     */
    suspend fun cancelAuth()

    /**
     * Email-based 2FA password recovery: ask Telegram to send a recovery
     * code to the registered email. Surface the result through [authStage]
     * (the password form swaps to recovery mode).
     */
    suspend fun requestPasswordRecovery()

    /** Submit the recovery code received via email. */
    suspend fun recoverPassword(code: String)

    /** Clear any pending [authError] (e.g. once the user starts typing again). */
    fun clearAuthError()
}
