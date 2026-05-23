package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.Flow
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

    // ---- Channels / users ---------------------------------------------

    /**
     * Fetch the channel metadata bundle for [chatId] (title, handle, description,
     * subscriber count, mute / member flags). Returns null if the chat isn't a
     * channel-style supergroup or TDLib rejects the lookup.
     */
    suspend fun channelInfo(chatId: Long): ChannelInfo?

    /** Toggle the mute state of [chatId] in the user's notification settings. */
    suspend fun setMuted(chatId: Long, muted: Boolean)

    /** Subscribe the user to [chatId]. */
    suspend fun joinChat(chatId: Long)

    /** Unsubscribe from [chatId]. */
    suspend fun leaveChat(chatId: Long)

    /**
     * Fetch the public-profile bundle for [userId] (name, handle, bio, avatar,
     * presence, bot/support flags, personal channel link, birthday, common
     * groups count). Returns null if the user isn't visible or TDLib rejects
     * the lookup.
     */
    suspend fun userProfile(userId: Long): UserProfile?

    // ---- Links --------------------------------------------------------

    /**
     * Parse [uri] through TDLib's `GetInternalLinkType` (knows the full ~50
     * t.me / tg:// shape catalogue, runs offline, pre-auth-safe). Returns a
     * typed [DeepLink] for inbound dispatch into the app, or null when the
     * URL isn't recognisably Telegram-internal. The iOS stub returns null
     * for everything — guest-mode UI falls through to the OS handler via
     * [androidx.compose.ui.platform.UriHandler.openUri].
     */
    suspend fun resolveLink(uri: String): DeepLink?

    // ---- Feed / comments ---------------------------------------------

    /**
     * Live snapshot of the merged feed (the `PostsRepository.posts` flow on
     * Android). The Comments overlay reads this so the pinned anchor's
     * reaction chip / view count / comment count stay in sync with the feed
     * row underneath. iOS returns an empty list — guest-mode UI doesn't
     * surface a unified TDLib feed and the iOS Comments path never mounts.
     */
    val feedPosts: StateFlow<PersistentList<TimelinePost>>

    /**
     * Subscribe to live updates of a discussion thread. The flow emits
     * [ThreadState.Loading] immediately, resolves to [ThreadState.Ready]
     * (or [ThreadState.Error]) on first round-trip, then continues to emit
     * fresh `Ready` snapshots as TDLib's `Update*Message*` events stream in.
     *
     * [candidateMessageIds] lists every message in the originating post's
     * album (or just the single post id) — the backend picks the carrier
     * with `canGetMessageThread = true` and starts the conversation from
     * there.
     */
    fun observeThread(chatId: Long, candidateMessageIds: List<Long>): Flow<ThreadState>

    /**
     * Mark thread messages as read (TDLib `ViewMessages` against the
     * discussion supergroup). Called by the comments screen's dwell-ack
     * effect when a comment row sits in the viewport past the read-mark
     * dwell window.
     */
    suspend fun viewThreadMessages(threadChatId: Long, messageIds: List<Long>)
}
