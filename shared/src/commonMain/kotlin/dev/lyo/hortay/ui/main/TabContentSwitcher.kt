@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ComposeResourcesStringResolver
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.FeedSource
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.ui.channels.ChannelsScreen
import dev.lyo.hortay.ui.settings.SettingsScreen
import dev.lyo.hortay.ui.timeline.TimelineScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.link_not_found

/**
 * Tab content switcher. Tab swap = pure crossfade. fastEffectsSpec is M3E's correct
 * channel for non-spatial state changes; on the same spring the FloatingNavBar's
 * selection container/colour/icon-fill morph runs.
 *
 * [tabStateHolder] gives each tab its own independent saveable scope via
 * SaveableStateProvider(key = tab.name), so rememberSaveable /
 * rememberLazyListState / rememberScrollState inside each tab survive
 * AnimatedContent's mount/unmount lifecycle.
 *
 * For NavTab.Feed a NESTED per-channel provider wraps TimelineScreen so every
 * visited channel (and the all-feed "no filter" view) gets its own independent
 * scroll/search state.
 */
@Composable
internal fun TabContentSwitcher(
    selectedTab: NavTab,
    tabStateHolder: SaveableStateHolder,
    feed: FeedSource,
    backend: HortayBackend,
    bookmarks: BookmarkStore,
    ignoredChannels: IgnoredChannelsStore,
    guestMode: GuestModeStore,
    userMessages: UserMessageBus,
    startupPhase: StateFlow<StartupCoordinator.Phase>,
    padding: PaddingValues,
    feedOrder: FeedOrder,
    snapScroll: Boolean,
    homeTapTrigger: Long,
    coveredByOverlay: Boolean,
    scope: CoroutineScope,
    onHomeTapTriggerBump: () -> Unit,
    onSafelyOpenChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onPushChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onPushComments: (TimelinePost) -> Unit,
    onPostReportClick: (TimelinePost) -> Unit,
    canReportPost: (TimelinePost) -> Boolean,
    tdlibMarkAsRead: suspend (List<TimelinePost>) -> Unit,
) {
    val tabEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val res = remember { ComposeResourcesStringResolver() }

    AnimatedContent(
        targetState = selectedTab,
        transitionSpec = { fadeIn(tabEffectsSpec) togetherWith fadeOut(tabEffectsSpec) },
        label = "tab-switch",
        modifier = Modifier.fillMaxSize(),
    ) { tab ->
        tabStateHolder.SaveableStateProvider(key = tab.name) {
            when (tab) {
                NavTab.Feed -> {
                    // List screen stays mounted, detail screens render as nav-stack
                    // overlays OUTSIDE this tab branch. Keeping TimelineScreen
                    // mounted means scroll position is owned by LazyListState, one
                    // source of subscriptions, and overlays share vocabulary.
                    tabStateHolder.SaveableStateProvider(key = "feed-channel:__all__") {
                        TimelineScreen(
                            feed = feed,
                            backend = backend,
                            bookmarks = bookmarks,
                            contentPadding = padding,
                            showOnlyBookmarked = false,
                            onChannelOpen = { id, scrollTo -> onSafelyOpenChannel(id, scrollTo) },
                            onOpenComments = { post -> onPushComments(post) },
                            homeTapTrigger = homeTapTrigger,
                            onBrandTap = onHomeTapTriggerBump,
                            scrollToMessage = null,
                            onScrollHandled = {},
                            onScrollMissed = {
                                userMessages.post(
                                    res.getString(Res.string.link_not_found),
                                    UserMessageBus.Severity.Info,
                                )
                            },
                            startupPhase = startupPhase,
                            onReportClick = onPostReportClick,
                            canReport = canReportPost,
                            markAsRead = tdlibMarkAsRead,
                            feedOrder = feedOrder,
                            snapScroll = snapScroll,
                            coveredByOverlay = coveredByOverlay,
                        )
                    }
                }
                NavTab.Channels -> ChannelsScreen(
                    backend = backend,
                    contentPadding = padding,
                    onChannelClick = { chatId ->
                        onPushChannel(chatId, null)
                    },
                )
                NavTab.Saved -> TimelineScreen(
                    feed = feed,
                    backend = backend,
                    bookmarks = bookmarks,
                    contentPadding = padding,
                    showOnlyBookmarked = true,
                    onChannelOpen = { id, scrollTo -> onSafelyOpenChannel(id, scrollTo) },
                    onOpenComments = { post -> onPushComments(post) },
                    homeTapTrigger = 0L,
                    onBrandTap = {},
                    startupPhase = startupPhase,
                    onReportClick = onPostReportClick,
                    canReport = canReportPost,
                    markAsRead = tdlibMarkAsRead,
                    feedOrder = feedOrder,
                    snapScroll = snapScroll,
                    coveredByOverlay = coveredByOverlay,
                )
                NavTab.Profile -> SettingsScreen(
                    settings = backend.settingsStore,
                    stats = backend.stats,
                    contentPadding = padding,
                    ignoredChannels = ignoredChannels,
                    backend = backend,
                    onLogout = { scope.launch { backend.logOut() } },
                    // Flip the guest flag FIRST so the routing pass that follows
                    // logOut() settles on [WebModeScaffold] instead of looping
                    // through [AuthScreen] for a beat.
                    onEnterGuest = {
                        scope.launch {
                            guestMode.setGuest(true)
                            backend.logOut()
                        }
                    },
                    autoDownload = backend.autoDownload,
                )
            }
        }
    }
}
