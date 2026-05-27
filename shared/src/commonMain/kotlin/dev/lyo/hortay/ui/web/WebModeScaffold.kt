@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.web

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.lyo.hortay.PlatformLog
import dev.lyo.hortay.currentLanguageTag
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChatId
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.MessageId
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.data.NavTarget
import dev.lyo.hortay.data.NavStack
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebPostAdapter
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.composables.navigation.BackSwipeEdge
import dev.lyo.hortay.ui.composables.bars.FloatingNavBar
import dev.lyo.hortay.ui.main.LinkAwareScaffold
import dev.lyo.hortay.ui.composables.navigation.NavTab
import dev.lyo.hortay.ui.main.nextTapToken
import dev.lyo.hortay.ui.report.GuestReportOutcome
import dev.lyo.hortay.ui.report.LocalGuestReportDelegate
import dev.lyo.hortay.ui.composables.dialogs.ReportInstructionDialog
import dev.lyo.hortay.ui.settings.SettingsScreen
import dev.lyo.hortay.ui.timeline.LocalReadCursors
import dev.lyo.hortay.ui.timeline.TimelineScreen
import androidx.compose.runtime.CompositionLocalProvider
import dev.lyo.hortay.data.TimelinePost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.link_hashtag_search
import hortay.shared.generated.resources.link_hashtag_search_in_channel
import hortay.shared.generated.resources.web_add_channel
import hortay.shared.generated.resources.web_comments_unavailable
import hortay.shared.generated.resources.web_comments_unavailable_title
import hortay.shared.generated.resources.web_deeplink_signin_required
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Top-level container for guest (anonymous) reading mode. Uses the SAME
 * Composables as [dev.lyo.hortay.ui.main.MainScaffold]:
 *   - [FloatingNavBar] / [NavTab] — same bottom nav, same four tabs
 *   - [TimelineScreen] — same feed renderer, driven by [WebFeedSource] via the
 *     shared [dev.lyo.hortay.data.FeedSource] interface; TDLib-only services
 *     (commentsRepo, folders, translations, channelActions, tdlibRepo) are
 *     passed null so the screen hides those affordances cleanly.
 *   - [SettingsScreen] — same screen, same SectionLabel / SettingsRow primitives;
 *     guest-mode parameters (onSignIn, onClearWebCache) flip the rendered set
 *     of sections without forking the screen.
 *
 * Web-specific UI files remaining: this scaffold (mode router) and
 * [WebChannelsScreen] / [AddChannelSheet] (channel-list + smart-paste flow
 * tied to the web subscription store; nothing equivalent exists in TDLib mode).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebModeScaffold(
    backend: HortayBackend,
    bookmarks: BookmarkStore,
    ignoredChannels: IgnoredChannelsStore,
    guestMode: GuestModeStore,
    webSubscriptions: SubscriptionsStore,
    webFeedSource: WebFeedSource,
    webRepository: WebRepository,
    webClient: WebTelegramClient,
    settingsStore: SettingsStore,
    linkDialogs: LinkDialogState,
    deepLinkRouter: DeepLinkRouter,
    nav: NavStack,
    appScope: CoroutineScope,
) {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.Feed) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // [addSheetOpen] + [deepLinkPrefill] are `rememberSaveable` so a user
    // mid-input in [AddChannelSheet] (typing a handle, reviewing a pasted URL)
    // doesn't lose the sheet and their typed text on rotation — the sheet's
    // own field state is saveable, but the parent's open/prefill flags need
    // to survive the same configuration change for the sheet to stay mounted.
    // Boolean and String? both serialise via the default Saver.
    //
    // Process-kill semantics are unchanged: rotation now preserves both flags,
    // and on a cold-launch after process death the deep-link router's
    // UNLIMITED channel on the graph re-delivers unconsumed events through the
    // collector path, which sets the pair again.
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    var deepLinkPrefill by rememberSaveable { mutableStateOf<String?>(null) }
    // guest-mode report instruction: set to the post's senderHandle after delegation fires.
    var showReportInstruction by remember { mutableStateOf(false) }
    // Monotonic counter incremented on each "Home" re-tap — TimelineScreen
    // observes it and scrolls the feed to top (or refreshes when already at
    // top). Same mechanism as MainScaffold so the home-tap-to-scroll gesture
    // works identically in both modes.
    var homeTapTrigger by rememberSaveable { mutableStateOf(0L) }

    // Channel back-stack — guest-mode counterpart to MainScaffold's TDLib stack.
    // Single polymorphic nav-stack on the Koin-singleton [NavStack]. Guest mode
    // only ever pushes [NavTarget.WebChannel]; the auth-mode variants (Channel,
    // Comments) are owned by [dev.lyo.hortay.ui.main.MainScaffold] and never
    // appear here because the two scaffolds never compose simultaneously
    // ([dev.lyo.hortay.MainActivity] routes auth.Ready → MainScaffold,
    // isGuest → WebModeScaffold).
    //
    // Same back-stack mechanics as MainScaffold — see [NavStack] KDoc.
    val stack = nav.entries
    val topEntry = stack.lastOrNull()

    // The active tab is NOT touched on push — under the nav-overlay the
    // user's originating tab keeps rendering, so a predictive-back swipe
    // reveals the right content underneath. Pop just removes the overlay.
    fun pushWebChannel(name: String) {
        nav.push(NavTarget.WebChannel(username = name.lowercase()))
    }

    fun popNav() {
        nav.pop()
    }

    fun clearNav() {
        nav.clear()
    }

    val scope = rememberCoroutineScope()
    val locale = remember { currentLanguageTag().substringBefore('-').lowercase() }
    // Guest-mode report delegation path. The CompositionLocal is wired by
    // [MainActivity] / [MainViewController] — Android maps to
    // `GuestReportDelegator`, iOS returns `GuestReportOutcome.AllFailed` so
    // the user gets the instruction dialog right away.
    val guestReport = LocalGuestReportDelegate.current
    val signInRequiredMsg = stringResource(Res.string.web_deeplink_signin_required)
    // Snackbar host lifted into the scaffold so deep-link rejection messages
    // ("sign in to open private channels") land regardless of which tab the
    // user is currently looking at. Same pattern as MainScaffold's userMessages
    // bus — TDLib mode's UserMessageBus has no analogue in guest mode, so this
    // is the lightest surface that satisfies the few cases we need.
    val snackbarHostState = remember { SnackbarHostState() }

    // Deep-link dispatcher. Mirrors MainScaffold's collector but speaks the
    // guest-mode dialect: only [DeepLink.PublicChannel] is actionable here
    // (we open AddChannelSheet pre-filled with the handle so the user can
    // confirm before subscribing — never auto-subscribing, which would join
    // arbitrary channels under the user's nose). Private and per-message
    // links require TDLib auth; we surface a snackbar nudging sign-in
    // instead of silently dropping them, which would feel broken when the
    // user clearly tapped a Telegram link.
    val systemUriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) {
        deepLinkRouter.events.collect { link ->
            // Per-link runCatching so a snackbar suspend cancellation or an unexpected
            // throw in one handler doesn't permanently silence the collector for the
            // rest of the process — matches the failure isolation MainScaffold uses.
            try {
                when (link) {
                    is DeepLink.PublicChannel -> {
                        deepLinkPrefill = link.handle
                        addSheetOpen = true
                    }
                    is DeepLink.PrivateChannel,
                    is DeepLink.Message,
                    is DeepLink.ChatInvite -> {
                        // Auth-only surfaces: nudge sign-in instead of silently dropping.
                        snackbarHostState.showSnackbar(signInRequiredMsg)
                    }
                    is DeepLink.External -> {
                        runCatching { systemUriHandler.openUri(link.originalUrl) }
                    }
                    is DeepLink.HashtagSearch -> {
                        // Mirrors MainScaffold: scoped snackbar when a channel scope
                        // was inferred (from `#tag@channel` text-entity suffix or
                        // PostBody's scoped LocalHashtagTap), generic otherwise.
                        val msg = if (link.channelHandle != null) {
                            getString(
                                Res.string.link_hashtag_search_in_channel,
                                link.tag,
                                "@${link.channelHandle}",
                            )
                        } else {
                            getString(Res.string.link_hashtag_search, link.tag)
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                PlatformLog.w("WebModeScaffold", "deep-link dispatch failed for $link", t)
            }
        }
    }

    // Predictive back: nav3's [NavDisplay] (mounted below) owns predictive-back
    // at stack depth 2+ via its internal NavigationBackHandler. At depth 1 nav3
    // disables its handler (Scene.previousEntries is empty), so we add a plain
    // [BackHandler] catching the final pop back to the underlying tab. Mirror
    // of MainScaffold's gate.
    BackHandler(enabled = topEntry != null && stack.size <= 1) { nav.pop() }
    BackHandler(enabled = topEntry == null && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    // See MainScaffold.kt for the holder-vs-PersistentMap rationale — guest
    // mode applies the same diff-apply pattern over its own cursor flow.
    val cursorHolder =
        dev.lyo.hortay.ui.timeline.rememberCursorHolder(webFeedSource.chatReadCursors)
    val feedOrder by settingsStore.feedOrder.collectAsStateWithLifecycle(
        initialValue = dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst,
    )
    val snapScroll by settingsStore.snapScroll.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val inlineVideoAutoplay by settingsStore.inlineVideoAutoplay.collectAsStateWithLifecycle(
        initialValue = true,
    )
    // Guest-mode dwell-ack wrapper. Groups the viewport batch by channel
    // (recovered from `senderHandle` since web posts have no real chatId) and
    // advances each channel's local cursor to the highest seq in the batch.
    // Result mirrors TDLib's "lastReadInboxMessageId moved up to message X":
    // the channel_read_cursor row gets MAX-clamped to the freshest seen post.
    //
    // `remember`-wrapped on webRepository so the lambda instance is stable
    // across WebModeScaffold recompositions. TimelineScreen uses this as a key for
    // `interactions = remember(...)` / `ackedRead = remember(markAsRead)`; a fresh
    // closure per recompose would invalidate those blocks and trigger redundant
    // markChannelRead writes on every dwell-batch evaluation.
    val webMarkAsRead: suspend (List<TimelinePost>) -> Unit = remember(webRepository) {
        { batch ->
            batch.groupBy { it.senderHandle?.removePrefix("@")?.lowercase() ?: "" }
                .forEach { (username, group) ->
                    if (username.isNotEmpty()) {
                        webRepository.markChannelRead(username, group.maxOf { it.id.value })
                    }
                }
        }
    }

    // Reverse-lookup chatId → username. WebPostAdapter.stableChatId is a stable
    // hash of the lowercased username; reading the live channels StateFlow at
    // tap time picks up newly-subscribed channels without needing a recomposition.
    // O(N) per tap is fine — N caps at the user's subscription set (≤200 in
    // practice), and the lambda only runs on a deliberate channel-name tap.
    val resolveUsername: (Long) -> String? = { chatId ->
        webFeedSource.channels.value.firstOrNull {
            WebPostAdapter.stableChatId(it.info.username) == chatId
        }?.info?.username
    }
    // Post-tap in guest mode opens the same post-detail surface TDLib mode
    // uses — [CommentsScreen] with the frozen anchor pinned at the top — but
    // with an empty-state hero in place of the thread body explaining why
    // replies aren't reachable here. Reuses the auth-mode [NavTarget.Comments]
    // entry: same nav-stack mechanics (predictive back, saveable state holder),
    // same screen, just a [CommentsDisabledOverride] supplied below so the
    // screen short-circuits its repository wiring. Previous behaviour was a
    // bare snackbar with the same copy — kept the user from getting to the
    // post detail at all.
    val commentsDisabledTitle = stringResource(Res.string.web_comments_unavailable_title)
    val commentsDisabledBody = stringResource(Res.string.web_comments_unavailable)
    val webCommentsOverride = remember(commentsDisabledTitle, commentsDisabledBody) {
        dev.lyo.hortay.ui.comments.CommentsDisabledOverride(
            symbol = "chat_bubble",
            title = commentsDisabledTitle,
            body = commentsDisabledBody,
        )
    }
    val onGuestPostClick: (TimelinePost) -> Unit = remember(nav) {
        { post -> nav.push(NavTarget.Comments(anchor = post)) }
    }
    // Feed → channel-name tap routes through the same WebChannelScreen overlay
    // that the Channels tab uses. resolveUsername returns null for channels not
    // in our subscriptions (e.g. a forwarded-from chip pointing at a stranger's
    // channel) — fall back to opening t.me/<u> in the system browser so the tap
    // is never silently dead.
    val onFeedChannelOpen: (ChatId, MessageId?) -> Unit = remember(snackbarHostState) {
        { chatId, _ ->
            val u = resolveUsername(chatId.value)
            if (u != null) pushWebChannel(u)
            else systemUriHandler.openUri("https://t.me/")
        }
    }
    LinkAwareScaffold(
        backend = backend,
        router = deepLinkRouter,
        linkDialogs = linkDialogs,
        scope = appScope,
    ) {
    CompositionLocalProvider(
        LocalReadCursors provides cursorHolder,
        dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay provides inlineVideoAutoplay,
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
        bottomBar = {
            // Hide nav-bar inside a drill (same rationale as MainScaffold).
            // Animated through M3E motion so the surrounding content padding
            // eases instead of snapping when the overlay pushes / pops.
            androidx.compose.animation.AnimatedVisibility(
                visible = topEntry == null,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + androidx.compose.animation.fadeIn(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + androidx.compose.animation.fadeOut(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ),
            ) {
                FloatingNavBar(
                    selected = selectedTab,
                    onSelect = { tab ->
                        val reselectingActiveFeed =
                            tab == NavTab.Feed && tab == selectedTab
                        if (reselectingActiveFeed) homeTapTrigger = nextTapToken()
                        selectedTab = tab
                    },
                )
            }
        },
        floatingActionButton = {
            // Hide the FAB inside a drill (the overlay owns the surface).
            // Symmetric M3E fade so it doesn't pop in/out abruptly.
            androidx.compose.animation.AnimatedVisibility(
                visible = topEntry == null,
                enter = androidx.compose.animation.fadeIn(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ) + androidx.compose.animation.scaleIn(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
                exit = androidx.compose.animation.fadeOut(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ) + androidx.compose.animation.scaleOut(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
            ) {
            // Primary "add channel" action. Surfaced on tabs where adding a
            // channel is contextually meaningful — Feed (where the user reads)
            // and Channels (where the list of subscriptions lives). Settings
            // and Saved hide it: clicking it there would feel context-mismatched.
            // ExtendedFab (with text label) on the empty-channels case so the
            // first-time user can't miss it; collapses to icon-only once posts
            // exist and the affordance becomes secondary.
            if (selectedTab == NavTab.Feed || selectedTab == NavTab.Channels) {
                // Subscribed via Lifecycle so the FAB collapses the moment the user
                // adds their first channel. Earlier `channels.value.any { … }` was a
                // raw StateFlow read inside composition — Compose never re-subscribed,
                // so the extended FAB stayed expanded with the "Add channel" label even
                // after subscriptions existed. derivedStateOf scopes recomposition to
                // the boolean: only an actual any/none flip propagates further.
                val channels by webFeedSource.channels.collectAsStateWithLifecycle()
                val hasChannels = channels.any { it.isSubscribed }
                // No manual padding here — Scaffold positions the FAB above the
                // bottomBar automatically. An earlier 88dp bottom padding stacked
                // on top of Scaffold's own offset and floated the button much too
                // high above the FloatingNavBar.
                ExtendedFloatingActionButton(
                    onClick = { addSheetOpen = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    expanded = !hasChannels,
                    icon = {
                        Symbol(
                            name = "add",
                            contentDescription = stringResource(Res.string.web_add_channel),
                            size = 24.dp,
                        )
                    },
                    text = { Text(stringResource(Res.string.web_add_channel)) },
                )
            }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Tab swap = pure crossfade (no spatial component) — destination
            // switch, not depth. fastEffectsSpec is M3E's correct channel for
            // non-spatial state changes. Captured here for the non-composable
            // transitionSpec lambda.
            val tabEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

            // SaveableStateHolder for tab-level state preservation. Each tab gets
            // its own independent saveable scope via SaveableStateProvider(tab.name),
            // so rememberSaveable / rememberLazyListState / rememberScrollState
            // inside each tab survive AnimatedContent's mount/unmount lifecycle.
            // The user's scroll position on the guest Feed, Channels, Saved and
            // Profile tabs is preserved across tab switches without any extra state
            // in each screen.
            val tabStateHolder = rememberSaveableStateHolder()

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tabEffectsSpec) togetherWith fadeOut(tabEffectsSpec)
                },
                label = "web-tab-switch",
                modifier = Modifier.fillMaxSize(),
            ) { tab ->
                tabStateHolder.SaveableStateProvider(key = tab.name) {
                when (tab) {
                    NavTab.Feed -> {
                        // Overlay pattern (mirror MainScaffold): TimelineScreen is
                        // ALWAYS mounted in the Feed tab; WebChannelScreen renders
                        // as a nav-stack overlay outside this tab branch (the top-2
                        // entries of [nav.stack] drawn below this Box block).
                        // Keeping the feed mounted preserves scroll position across
                        // channel drills without the SaveableStateProvider serialise/
                        // restore cycle that would otherwise mis-anchor the user
                        // when the underlying post list mutates while they're away.
                        tabStateHolder.SaveableStateProvider(key = "web-feed:__all__") {
                            TimelineScreen(
                                feed = webFeedSource,
                                bookmarks = bookmarks,
                                contentPadding = padding,
                                showOnlyBookmarked = false,
                                onChannelOpen = onFeedChannelOpen,
                                onOpenComments = onGuestPostClick,
                                homeTapTrigger = homeTapTrigger,
                                onBrandTap = { homeTapTrigger = nextTapToken() },
                                onSearchClick = { searchOpen = true },
                                topBarBadge = { GuestModeBadge() },
                                onReportClick = { post ->
                                    val outcome = guestReport(
                                        post.senderHandle?.removePrefix("@"),
                                        if (post.id.value != 0L) post.id.value else null,
                                    )
                                    if (outcome == GuestReportOutcome.OpenedTelegram ||
                                        outcome == GuestReportOutcome.OpenedWeb) {
                                        showReportInstruction = true
                                    }
                                },
                                canReport = { true },
                                markAsRead = webMarkAsRead,
                                feedOrder = feedOrder,
                                snapScroll = snapScroll,
                                // Reserve room for the "Add channel" FAB that this
                                // scaffold parks at BottomEnd. Without this the
                                // floating "↓ N" unread pill (also BottomEnd, owned
                                // by TimelineScreen) lands directly under the FAB
                                // and is un-tappable. ExtendedFAB ~56.dp + 16.dp
                                // gap → 72.dp. Only the Feed tab needs this; the
                                // Saved-tab call below leaves the default 0.dp
                                // because the FAB is hidden there.
                                unreadPillExtraBottomPadding = 72.dp,
                            )
                        }
                    }

                    NavTab.Channels -> WebChannelsScreen(
                        webFeedSource = webFeedSource,
                        ignoredChannels = ignoredChannels,
                        subscriptions = webSubscriptions,
                        contentPadding = padding,
                        onChannelClick = { username ->
                            pushWebChannel(username)
                        },
                        onAddChannel = { addSheetOpen = true },
                    )

                    NavTab.Saved -> TimelineScreen(
                        feed = webFeedSource,
                        bookmarks = bookmarks,
                        contentPadding = padding,
                        showOnlyBookmarked = true,
                        onChannelOpen = onFeedChannelOpen,
                        onOpenComments = onGuestPostClick,
                        onReportClick = { post ->
                            val outcome = guestReport(
                                post.senderHandle?.removePrefix("@"),
                                if (post.id.value != 0L) post.id.value else null,
                            )
                            if (outcome == GuestReportOutcome.OpenedTelegram ||
                                outcome == GuestReportOutcome.OpenedWeb) {
                                showReportInstruction = true
                            }
                        },
                        canReport = { true },
                        markAsRead = webMarkAsRead,
                        feedOrder = feedOrder,
                        snapScroll = snapScroll,
                    )

                    NavTab.Profile -> SettingsScreen(
                        settings = settingsStore,
                        stats = null,
                        contentPadding = padding,
                        onLogout = null,
                        onSignIn = { scope.launch { guestMode.setGuest(false) } },
                        // Combined wipe-and-refetch so the user sees fresh
                        // content immediately, not an empty feed waiting for
                        // the next tier-2 sweep. Subscriptions survive.
                        onClearWebCache = { webFeedSource.clearCacheAndRefresh() },
                        ignoredChannels = ignoredChannels,
                        // Guest-mode resolver: walk the in-memory channels
                        // list for a row whose username hashes to the given
                        // chatId. Cheap — typical subscription set is < 200,
                        // resolution happens once per hidden chatId on screen
                        // entry, and the StateFlow is already a snapshot the
                        // composable holds.
                        webChannelByChatId = { chatId ->
                            webFeedSource.channels.value
                                .firstOrNull {
                                    dev.lyo.hortay.data.web.WebPostAdapter.stableChatId(
                                        it.info.username,
                                    ) == chatId
                                }
                                ?.let {
                                    dev.lyo.hortay.ui.settings.WebChannelDescriptor(
                                        title = it.info.title,
                                        username = it.info.username,
                                    )
                                }
                        },
                    )
                }
                }
            }

            // nav3 NavDisplay renders the top scene and animates between scenes
            // on push/pop, including predictive-back peek of the previous scene
            // underneath. Per-entry SaveableStateProvider + ViewModelStoreOwner
            // are wired through the decorator chain — same contract MainScaffold
            // rides. Empty-stack guard: nav3 requires non-empty backStack.
            if (stack.isNotEmpty()) {
                NavDisplay(
                    backStack = nav.entries,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { popNav() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider {
                        entry<NavTarget.WebChannel>(
                            clazzContentKey = { it.entryId },
                        ) { target ->
                            WebChannelScreen(
                                username = target.username,
                                bookmarks = bookmarks,
                                webRepository = webRepository,
                                webFeedSource = webFeedSource,
                                contentPadding = padding,
                                onBack = ::popNav,
                                onPostClick = onGuestPostClick,
                                feedOrder = feedOrder,
                            )
                        }
                        entry<NavTarget.Comments>(
                            clazzContentKey = { it.entryId },
                        ) { target ->
                            // Guest mode has no TDLib session → no backend
                            // to live-sync the anchor or thread against.
                            // The screen renders the frozen NavTarget
                            // snapshot and shows [webCommentsOverride] as
                            // the empty-state hero in place of the thread
                            // body.
                            dev.lyo.hortay.ui.comments.CommentsScreen(
                                post = target.anchor,
                                backend = null,
                                onDismiss = ::popNav,
                                disabledOverride = webCommentsOverride,
                            )
                        }
                        // Defensive: NavTarget.Channel never reaches this
                        // scaffold (MainActivity routes auth mode to
                        // MainScaffold). Render nothing rather than crash via
                        // the throwing default fallback if routing changes.
                        entry<NavTarget.Channel>(
                            clazzContentKey = { it.entryId },
                        ) { _ -> Unit }
                    },
                )
            }
        }
    }

    if (addSheetOpen) {
        AddChannelSheet(
            feedSource = webFeedSource,
            repository = webRepository,
            client = webClient,
            locale = locale,
            // One-shot: clear the prefill on dismiss so a manual reopen lands
            // back on the clipboard auto-paste path instead of looping the user
            // through the same deep-link target every time they tap "Add channel".
            onDismiss = {
                addSheetOpen = false
                deepLinkPrefill = null
            },
            onSignIn = { scope.launch { guestMode.setGuest(false) } },
            prefilledUsername = deepLinkPrefill,
        )
    }

    // Cross-channel local search overlay. Lives at the scaffold level (not as
    // a tab) so it can grab the full screen, including the area normally
    // occupied by the FloatingNavBar — search-as-an-overlay is the canonical
    // Material 3 pattern, and pinning it under nav would make the keyboard
    // collide with results.
    if (searchOpen) {
        WebSearchScreen(
            repository = webRepository,
            bookmarks = bookmarks,
            onDismiss = { searchOpen = false },
        )
    }

    // Instruction dialog: shown after guest-mode delegation opens Telegram or a
    // web tab. Tells the user how to complete the report in the external surface.
    if (showReportInstruction) {
        ReportInstructionDialog(onDismiss = { showReportInstruction = false })
    }
    }
    }
}
