package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.PublicHandleResult
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportFlowController
import dev.lyo.hortay.data.report.ReportLogStore
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
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
     * Join a chat by invite link. Returns the resolved chat id on success
     * (callers push into it), null on failure (surfaced via `UserMessageBus`).
     */
    suspend fun joinByInvite(inviteLink: String): Long?

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
     * Live single-post stream of fresh arrivals (TDLib `UpdateNewMessage` →
     * `MessageMapper` pipeline on Android). Used by `NewPostsPill` to count
     * arrivals while the user reads, and by `MediaAutoDownloader` to fan
     * downloads off the same event. Emits nothing on iOS guest mode.
     */
    val newArrivals: SharedFlow<TimelinePost>

    /** Replace `_posts` with a fresh snapshot from TDLib. PTR + cold-start path. */
    suspend fun refreshFeed()

    /**
     * Live set of chat ids the user has archived. The home feed hides these by
     * default and surfaces them only under the dedicated Archive scope.
     */
    val archivedChatIds: StateFlow<Set<Long>>

    /**
     * Folder tab metadata facade — null when no TDLib session backs the
     * timeline (iOS guest mode, pre-auth). Authenticated callers get a live
     * projection of the user's chat folders.
     */
    val folders: FoldersFacade?

    /**
     * Load older history for [chatId] (channel-screen pagination). Returns the
     * number of new posts that landed; zero means "no more history available".
     */
    suspend fun loadOlder(chatId: Long): Int

    /**
     * Pull a window of history around [anchorMessageId] in [chatId] so a deep
     * link can render the anchor with context on either side. Returns `true`
     * when the anchor (or an album sibling) is now in the merged feed.
     */
    suspend fun loadHistoryAround(chatId: Long, anchorMessageId: Long): Boolean

    /** Bump TDLib's `OpenChat` refcount via [ChatPresence]. */
    suspend fun openChat(chatId: Long)

    /** Symmetric [openChat] partner. Wrap critical pairs in `NonCancellable`. */
    suspend fun closeChat(chatId: Long)

    /**
     * Mark [messageIds] in [chatId] as viewed (TDLib `ViewMessages`). The
     * channel screen's dwell-ack effect calls this when a message sits in
     * the viewport past the read-mark dwell window.
     */
    suspend fun viewMessages(chatId: Long, messageIds: List<Long>)

    /**
     * Warm the per-channel history slice — drains `GetChatHistory` once and
     * merges into `_posts`. Called from `pushChannel` before mounting the
     * channel screen so OldestUnreadFirst lands with the full slice in one
     * frame. Returns `Result.success` even when TDLib serves an empty page;
     * `Result.failure` carries the underlying exception for retry chrome.
     */
    suspend fun loadChannelHistory(chatId: Long): Result<Unit>

    /**
     * True when [loadChannelHistory] has already drained successfully for
     * [chatId] this session — push paths use this to short-circuit the
     * await on cooldown (warm/cache opens stay instant).
     */
    fun hasWarmChannelHistory(chatId: Long): Boolean

    /**
     * Chat display title from TDLib's local cache (or one-shot `GetChat` on
     * miss). Used by channel chrome and link-preview fallbacks.
     */
    suspend fun chatTitle(chatId: Long): String?

    /**
     * Subscriber count for [chatId] — supergroup-only. Suspending variant;
     * hits TDLib once on cache miss, then served from the local mirror.
     */
    suspend fun channelSubscribers(chatId: Long): Int?

    /**
     * Synchronous mirror read of [channelSubscribers]. Returns null when the
     * value hasn't been fetched yet — callers fall through to the suspending
     * variant in that case. Composed-into `ChannelViewModel` seed paths.
     */
    fun channelSubscribersCached(chatId: Long): Int?

    /**
     * Chat avatar bundle: TDLib file id of the small (160 dp) variant plus
     * the inline minithumbnail bytes (~200 B, embedded in every chat photo
     * so we can paint something while the file downloads).
     */
    suspend fun chatAvatar(chatId: Long): Pair<Int?, ByteArray?>?

    /**
     * Search the channel's history for [query]. Returns matches as ready-to-
     * render [TimelinePost]s. Throttled by TDLib's per-chat search limits.
     */
    suspend fun searchInChannel(chatId: Long, query: String): List<TimelinePost>

    /**
     * Optimistic reaction flip — paints the chip immediately on the local
     * snapshot so the tap feels instant. The eventual `UpdateMessageInteractionInfo`
     * from TDLib overwrites with server truth via the same `_posts.update`
     * path. On RPC failure callers re-invoke with the inverted [nowChosen]
     * to roll back the visual change.
     */
    fun applyOptimisticReaction(
        chatId: Long,
        messageId: Long,
        kind: ReactionKind,
        nowChosen: Boolean,
    )

    /**
     * Optimistic poll-vote flip — marks selected rows with `isBeingChosen`
     * so the tap shows a shimmer immediately. Eventual `UpdateMessageContent`
     * settles the authoritative percentages.
     */
    fun applyOptimisticPollAnswer(chatId: Long, messageId: Long, chosenIndices: IntArray)

    /** Drop `isBeingChosen` shimmer; if [revert] also reset `isChosen`. */
    fun clearPollPending(chatId: Long, messageId: Long, revert: Boolean)

    /** Send the reaction toggle to TDLib. Returns success for rollback gating. */
    suspend fun toggleReaction(
        chatId: Long,
        messageId: Long,
        kind: ReactionKind,
        isChosen: Boolean,
    ): Boolean

    /** Send the poll-vote commit to TDLib. Returns success for rollback gating. */
    suspend fun setPollAnswer(chatId: Long, messageId: Long, optionIds: IntArray): Boolean

    /**
     * Resolve a Telegram public `@handle` (without the leading `@`) to a
     * typed [PublicHandleResult] — channel chat id / user id / bot reject /
     * not-found. Drives the deep-link dispatcher and `@mention` taps.
     */
    suspend fun resolvePublicHandle(handle: String): PublicHandleResult

    /**
     * Twin of [resolvePublicHandle] keyed on a TDLib chat id. Used when we
     * already have an id from a forward header / reply target and need to
     * decide whether to push a channel screen or open the user sheet.
     */
    suspend fun resolveChatKind(chatId: Long): PublicHandleResult

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

    /**
     * Warm the comments-thread cache for [chatId] / [candidateMessageIds]
     * — usually invoked when a post settles in the viewport so that
     * tapping the comments affordance opens the overlay without an extra
     * RPC round-trip.
     */
    suspend fun prefetchThread(chatId: Long, candidateMessageIds: List<Long>)

    /**
     * Optimistic reaction flip on a comments-thread message. Separate from
     * [applyOptimisticReaction] (feed posts) because comments overrides live
     * on the thread observer's own snapshot, not on the feed `_posts` stream.
     */
    fun applyCommentOptimisticReaction(
        threadChatId: Long,
        messageId: Long,
        current: Reactions,
        kind: ReactionKind,
        nowChosen: Boolean,
    )

    /** Drop a previously-applied comment optimistic-reaction override. */
    fun clearCommentOptimisticReaction(threadChatId: Long, messageId: Long)

    /**
     * TDLib-minted canonical share URL for [post]: handles album-anchor,
     * topic, message-thread shapes Telegram considers canonical. Returns
     * null when no message link is available (restricted source chat,
     * `canGetLink = false`) — callers fall back to a hand-rolled URL.
     */
    suspend fun canonicalShareUrl(post: TimelinePost): String?

    // ---- Auth / session ----------------------------------------------

    /**
     * `true` once TDLib has reached `AuthStage.Ready`. Distinct from
     * [authStage] so consumers (top-bar avatar, settings logout row) can
     * gate UI on a simple boolean without pattern-matching the full state
     * machine. iOS stub stays `false` forever — guest mode never reaches
     * authenticated state without TDLib.
     */
    val isAuthenticated: StateFlow<Boolean>

    /**
     * Sign out of the current Telegram account. Triggers TDLib's
     * `LogOut → AuthorizationStateClosing → Closed` chain; the surrounding
     * `AppGraph` flushes every per-account cache before TDLib respawns a
     * fresh session for the AuthScreen. No-op on iOS.
     */
    suspend fun logOut()

    // ---- Reporting (CSAE) --------------------------------------------

    /**
     * Drives the multi-step TDLib ReportChat flow. On iOS this is a stub
     * that returns immediate failure — guest UI gates the Report action on
     * [isAuthenticated] and routes through `LocalGuestReportDelegate`
     * instead.
     */
    val reportController: ReportFlowController

    /**
     * Process-wide single-slot tracker for "Report sheet currently open
     * against (chatId, messageId)". Backed by a [MutableStateFlow] so the
     * scaffold can show / hide the [ui.report.ReportFlowSheet] reactively.
     */
    val reportDialogs: ReportDialogState

    /**
     * Append-only audit log of every report attempt (auth + guest mode).
     * Surfaced in Settings for compliance review. iOS guest mode writes
     * here via the local delegate adapter.
     */
    val reportLogStore: ReportLogStore

    /**
     * One-shot explainer flag: the first time the user opens the Report
     * flow we show [ui.report.ReportAboutDialog] before the sheet content.
     * Persisted across launches.
     */
    val reportExplainerStore: ReportExplainerStore

    // ---- Settings / translations -------------------------------------

    /** User preferences (theme, feed order, snap scroll, etc). */
    val settingsStore: SettingsStore

    /**
     * In-memory translation cache (TDLib `TranslateMessageText` on Android).
     * Null on iOS guest mode — no TDLib backing service, and the translate
     * button is hidden in the post chrome when this is null.
     */
    val translations: TranslationsFacade?
}
