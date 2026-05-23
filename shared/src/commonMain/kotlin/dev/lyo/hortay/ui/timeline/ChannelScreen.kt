@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.TranslationKey
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.data.isUnplayableVideo
import dev.lyo.hortay.data.orderedFor
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.ui.channels.ChannelInfoSheet
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalIsCenteredItem
import dev.lyo.hortay.ui.media.LocalIsHighlightedItem
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.LocalScrollGate
import dev.lyo.hortay.ui.media.rememberDeferredLoading
import dev.lyo.hortay.ui.theme.HortayExpressive
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.action_back
import hortay.shared.generated.resources.action_clear
import hortay.shared.generated.resources.action_search
import hortay.shared.generated.resources.channel_empty_body
import hortay.shared.generated.resources.channel_empty_title
import hortay.shared.generated.resources.timeline_search_empty
import hortay.shared.generated.resources.timeline_search_in_channel
import hortay.shared.generated.resources.timeline_subscribers
import org.jetbrains.compose.resources.stringResource

// FlowPreview opt-in stays: Flow.debounce(Long) is still preview-marked in
// kotlinx-coroutines 1.10.1.

/**
 * Single-channel post feed. Each channel gets a proper dedicated Composable with
 * its own [ChannelViewModel] instance (keyed on [chatId]) and its own list state,
 * so navigating between channels or back to the all-feed never shares stale
 * scroll / search / loading state.
 *
 * @param onChannelOpen Called when the user taps a channel header, a forward-source
 *   chip, or an inline reply / quote card whose target is a DIFFERENT channel than
 *   the one currently displayed. The second parameter is the optional messageId to
 *   land on inside the destination channel.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ChannelScreen(
    chatId: Long,
    backend: HortayBackend,
    bookmarks: BookmarkStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenComments: (TimelinePost) -> Unit,
    onChannelOpen: (chatId: Long, scrollToMessageId: Long?) -> Unit,
    scrollToMessage: Pair<Long, Long>? = null,
    onScrollHandled: () -> Unit = {},
    onScrollMissed: () -> Unit = {},
    onReportClick: (TimelinePost) -> Unit = {},
    canReport: (TimelinePost) -> Boolean = { false },
    onReportChannel: (() -> Unit)? = null,
    ignoredChannels: IgnoredChannelsStore? = null,
    feedOrder: FeedOrder = FeedOrder.OldestUnreadFirst,
    startupPhase: StateFlow<StartupCoordinator.Phase>? = null,
) {
    // Per-channel VM keyed by chatId — each channel gets its own instance.
    val vm: ChannelViewModel = viewModel(
        key = "channel:$chatId",
        factory = remember(backend, bookmarks, chatId, scrollToMessage) {
            viewModelFactory {
                initializer {
                    ChannelViewModel(
                        backend = backend,
                        bookmarks = bookmarks,
                        chatId = chatId,
                        scrollToMessageId = scrollToMessage?.second,
                    )
                }
            }
        },
    )

    val data by vm.data.collectAsStateWithLifecycle()
    val posts = when (val d = data) {
        is ChannelData.Loaded -> d.posts
        ChannelData.Loading -> persistentListOf()
    }
    val attemptedAround by vm.attemptedAround.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val channelTitle by vm.channelTitle.collectAsStateWithLifecycle()
    val channelSubscribers by vm.channelSubscribers.collectAsStateWithLifecycle()
    val channelAvatarFileId by vm.channelAvatarFileId.collectAsStateWithLifecycle()
    val channelAvatarThumb by vm.channelAvatarThumb.collectAsStateWithLifecycle()
    val bookmarkedKeys by vm.bookmarkedKeys.collectAsStateWithLifecycle()
    val searchActive by vm.searchActive.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val translationsFacade = backend.translations
    val translationsMap = translationsFacade?.translations?.collectAsStateWithLifecycle()?.value.orEmpty()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val viewer = LocalMediaViewer.current

    // Info sheet: hoisted local state, dismissed by setting false.
    var infoSheetVisible by remember { mutableStateOf(false) }

    // Search-mode back-handler. Collapses search overlay instead of popping the
    // channel off the back-stack — leaf-scoped BackHandler takes priority over
    // parent BackHandlers, which is exactly the dispatch rule we need.
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    BackHandler(enabled = searchActive) { vm.setSearchActive(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val cursorHolder = LocalReadCursors.current
    val displayedItems = remember(posts, searchActive, searchResults, feedOrder) {
        val source = if (searchActive) searchResults else posts.orderedFor(feedOrder)
        source.map(::FeedItem).toPersistentList()
    }

    val channelCursors = remember(chatId, feedOrder) { cursorHolder.snapshot() }
    val candidateChannelUiState = buildChannelUiState(
        data = data,
        items = displayedItems,
        scrollToMessageId = vm.scrollToMessageId,
        attemptedAround = attemptedAround,
        searchActive = searchActive,
        chatId = chatId,
        feedOrder = feedOrder,
        cursors = channelCursors,
    )
    val channelUiState = rememberLatchedChannelUiState(
        candidate = candidateChannelUiState,
        routeKey = chatId,
    )
    LaunchedEffect(scrollToMessage) {
        if (scrollToMessage != null) onScrollHandled()
    }
    LaunchedEffect(channelUiState) {
        if (channelUiState is ChannelUiState.Missing) onScrollMissed()
    }

    var highlightedPostKey by remember(chatId) { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(channelUiState, chatId) {
        val mid = (channelUiState as? ChannelUiState.Ready)?.highlightedMessageId ?: return@LaunchedEffect
        highlightedPostKey = chatId to mid
    }
    LaunchedEffect(highlightedPostKey) {
        if (highlightedPostKey == null) return@LaunchedEffect
        delay(CHANNEL_HIGHLIGHT_DURATION_MS)
        highlightedPostKey = null
    }
    var pendingScrollToMessage by remember(chatId) { mutableStateOf<Pair<Long, Long>?>(null) }

    val pinnedChannelSeed = rememberSaveable(chatId) { mutableIntStateOf(-1) }
    val candidateInitialIndex = (channelUiState as? ChannelUiState.Ready)?.initialIndex
    LaunchedEffect(chatId, candidateInitialIndex) {
        if (pinnedChannelSeed.intValue < 0 && candidateInitialIndex != null) {
            pinnedChannelSeed.intValue = candidateInitialIndex
        }
    }
    val initialIndexSeed = when {
        pinnedChannelSeed.intValue >= 0 -> pinnedChannelSeed.intValue
        candidateInitialIndex != null -> candidateInitialIndex
        else -> 0
    }
    val listState = rememberSaveable(
        chatId, initialIndexSeed,
        saver = LazyListState.Saver,
    ) {
        LazyListState(initialIndexSeed, 0)
    }

    rememberPendingScrollToMessage(
        displayedItems = displayedItems,
        pendingTarget = pendingScrollToMessage,
        loadHistoryAround = { cid, mid -> backend.loadHistoryAround(cid, mid) },
        onLanded = { cid, mid, idx ->
            highlightedPostKey = cid to mid
            listState.scrollToItem(idx)
            pendingScrollToMessage = null
        },
        onMissed = {
            pendingScrollToMessage = null
            onScrollMissed()
        },
    )

    // Pagination: direction-aware near-edge snapshotFlow → VM.loadOlderIfPossible().
    // Older history lives at opposite ends of the list depending on feedOrder.
    LaunchedEffect(listState, chatId, feedOrder) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(total, firstVisible, lastVisible)
        }
            .distinctUntilChanged()
            .collect { (total, first, last) ->
                if (total == 0 || first < 0 || last < 0) return@collect
                val nearOlderEdge = when (feedOrder) {
                    FeedOrder.Newest -> last >= total - CHANNEL_PAGINATION_THRESHOLD
                    FeedOrder.OldestUnreadFirst -> first <= CHANNEL_PAGINATION_THRESHOLD
                }
                if (nearOlderEdge) {
                    vm.loadOlderIfPossible()
                }
            }
    }

    // Read-state acks: viewport-stable dwell → viewMessages.
    val ackedRead = rememberReadAckDwell(
        listState = listState,
        displayedItems = displayedItems,
        ackKey = chatId,
        markAsRead = { fresh ->
            fresh.groupBy { it.chatId }.forEach { (cid, group) ->
                val ids = group.flatMap { post ->
                    post.albumMessageIds.ifEmpty { listOf(post.id) }
                }.distinct()
                vm.viewMessages(cid, ids)
            }
        },
        scope = scope,
        dwellMs = CHANNEL_READ_DWELL_MS,
    )

    // Comments-thread prefetch — extracted to [rememberCommentsPrefetch], shared
    // with [TimelineScreen].
    rememberCommentsPrefetch(
        listState = listState,
        displayedItems = displayedItems,
        startupPhase = startupPhase,
        prefetchThread = backend::prefetchThread,
        debounceMs = CHANNEL_PREFETCH_DEBOUNCE_MS,
        maxConcurrent = CHANNEL_COMMENTS_PREFETCH_LIMIT,
    )

    // --- State wrappers for lambdas (same rememberUpdatedState pattern as TimelineScreen) ---
    val bookmarkedState = rememberUpdatedState(bookmarkedKeys)
    val translationsState = rememberUpdatedState(translationsMap)
    val onChannelOpenState = rememberUpdatedState(onChannelOpen)
    val onOpenCommentsState = rememberUpdatedState(onOpenComments)
    val markPostReadState = rememberUpdatedState({ post: TimelinePost ->
        val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
        val unacked = ids.filter { (post.chatId to it) !in ackedRead }
        if (unacked.isNotEmpty()) {
            unacked.forEach { ackedRead.add(post.chatId to it) }
            scope.launch { vm.viewMessages(post.chatId, unacked) }
        }
    })

    fun lookupTranslation(post: TimelinePost): FormattedText? {
        val facade = translationsFacade ?: return null
        val map = translationsState.value
        val lang = facade.currentTargetLanguage()
        map[TranslationKey(post.chatId, post.id, lang)]?.let { return it }
        post.albumMessageIds.forEach { id ->
            map[TranslationKey(post.chatId, id, lang)]?.let { return it }
        }
        return null
    }

    val interactions = remember(vm, viewer, translationsFacade, backend, bookmarks, onReportClick, canReport) {
        PostInteractions(
            onMediaClick = { post, idx ->
                markPostReadState.value(post)
                val items = (post.content as? PostContent.PhotoAlbum)?.items.orEmpty()
                if (items.getOrNull(idx)?.isUnplayableVideo == true) {
                    scope.launch { PostActions.openInTelegram(uriHandler, backend, post) }
                } else {
                    viewer.openFor(post.content, idx)
                }
            },
            onChannelClick = { post ->
                if (post.chatId != chatId) onChannelOpenState.value(post.chatId, null)
            },
            onAuthorChatClick = { id ->
                if (id != chatId) onChannelOpenState.value(id, null)
            },
            onForwardSourceClick = { post ->
                val origin = post.forwardOrigin
                val sourceId = when (origin) {
                    is ForwardOrigin.Channel -> origin.sourceChatId
                    is ForwardOrigin.Chat -> origin.sourceChatId
                    else -> null
                }
                val sourceHandle = when (origin) {
                    is ForwardOrigin.Channel -> origin.sourceHandle
                    is ForwardOrigin.Chat -> origin.sourceHandle
                    else -> null
                }
                val sourceMessageId = (origin as? ForwardOrigin.Channel)?.sourceMessageId
                when {
                    sourceId != null -> onChannelOpenState.value(sourceId, sourceMessageId)
                    !sourceHandle.isNullOrBlank() -> {
                        val handle = sourceHandle.removePrefix("@")
                        val url = if (sourceMessageId != null) "https://t.me/$handle/$sourceMessageId"
                            else "https://t.me/$handle"
                        uriHandler.openUri(url)
                    }
                }
            },
            onQuotedSourceClick = { post ->
                post.reply?.let { r ->
                    if (r.replyToChatId == chatId) {
                        pendingScrollToMessage = chatId to r.replyToMessageId
                    } else {
                        onChannelOpenState.value(r.replyToChatId, r.replyToMessageId)
                    }
                }
            },
            onBookmarkClick = { post -> vm.toggleBookmark(post) },
            onShareClick = { post -> scope.launch { PostActions.share(backend, post) } },
            onCopyClick = { post -> PostActions.copyText(post) },
            onOpenClick = { post ->
                markPostReadState.value(post)
                scope.launch { PostActions.openInTelegram(uriHandler, backend, post) }
            },
            onTranslateClick = { post ->
                val facade = translationsFacade ?: return@PostInteractions
                scope.launch {
                    val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                    facade.translate(post.chatId, ids.first())
                }
            },
            onClearTranslationClick = { post ->
                val facade = translationsFacade ?: return@PostInteractions
                val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                ids.forEach { facade.clear(post.chatId, it) }
            },
            isTranslated = { post -> lookupTranslation(post) != null },
            translationFor = ::lookupTranslation,
            translateEnabled = translationsFacade != null,
            onReactionToggle = { post, item ->
                val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                val nowChosen = !item.isChosen
                backend.applyOptimisticReaction(post.chatId, target, item.kind, nowChosen)
                scope.launch {
                    val ok = backend.toggleReaction(
                        chatId = post.chatId,
                        messageId = target,
                        kind = item.kind,
                        isChosen = item.isChosen,
                    )
                    if (!ok) backend.applyOptimisticReaction(post.chatId, target, item.kind, item.isChosen)
                }
            },
            onPostClick = { post ->
                markPostReadState.value(post)
                onOpenCommentsState.value(post)
            },
            onPollVote = { post, indices ->
                val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                backend.applyOptimisticPollAnswer(post.chatId, target, indices)
                scope.launch {
                    val ok = backend.setPollAnswer(post.chatId, target, indices)
                    backend.clearPollPending(post.chatId, target, revert = !ok)
                }
            },
            pollVotingEnabled = true,
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedState.value },
            onReportClick = onReportClick,
            canReport = canReport,
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars),
                )
                ChannelTopBar(
                    channelTitle = channelTitle,
                    channelSubscribers = channelSubscribers,
                    channelAvatarFileId = channelAvatarFileId,
                    channelAvatarThumb = channelAvatarThumb,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    onBack = onBack,
                    onSearchToggle = { vm.setSearchActive(!searchActive) },
                    onSearchQueryChange = { vm.setSearchQuery(it) },
                    onTitleTap = { infoSheetVisible = true },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
            ) {
                val displayedList = when (channelUiState) {
                    is ChannelUiState.Ready -> channelUiState.items
                    ChannelUiState.Missing -> displayedItems
                    ChannelUiState.Resolving -> displayedItems
                }
                val isResolving = channelUiState is ChannelUiState.Resolving
                val showSkeleton = isResolving && rememberDeferredLoading(
                    pending = isResolving,
                    key = chatId,
                    graceMs = SCREEN_MOUNT_GRACE_MS,
                )
                when {
                    showSkeleton -> {
                        SkeletonFeed(modifier = Modifier.fillMaxSize())
                    }
                    isResolving -> {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                    displayedList.isEmpty() && !refreshing -> {
                        when {
                            searchActive && searchQuery.isNotBlank() -> ChannelSearchEmpty()
                            else -> ChannelEmptyState()
                        }
                    }
                    else -> {
                        val scrollGate = remember(listState) {
                            derivedStateOf { !listState.isScrollInProgress }
                        }

                        val centeredItemKeyState: androidx.compose.runtime.State<Any?> =
                            remember(listState) {
                                derivedStateOf {
                                    val info = listState.layoutInfo
                                    val visible = info.visibleItemsInfo
                                    if (visible.isEmpty()) return@derivedStateOf null
                                    val viewportCenter =
                                        (info.viewportStartOffset + info.viewportEndOffset) / 2
                                    visible.minByOrNull { item ->
                                        val itemCenter = item.offset + item.size / 2
                                        kotlin.math.abs(itemCenter - viewportCenter)
                                    }?.key
                                }
                            }

                        val cache = LocalMediaCache.current
                        val prefetchAnchor by remember(listState) {
                            derivedStateOf {
                                if (listState.isScrollInProgress) null
                                else listState.firstVisibleItemIndex
                            }
                        }
                        LaunchedEffect(prefetchAnchor, displayedList) {
                            val firstVisible = prefetchAnchor ?: return@LaunchedEffect
                            if (firstVisible >= displayedList.size) return@LaunchedEffect
                            val end = (firstVisible + CHANNEL_PREFETCH_AHEAD)
                                .coerceAtMost(displayedList.lastIndex)
                            for (idx in (firstVisible + 1)..end) {
                                val item = displayedList.getOrNull(idx) ?: continue
                                for (post in item.posts()) {
                                    for (fileId in post.content.posterFileIds()) {
                                        cache.ensure(fileId, DownloadPriority.Prefetch)
                                    }
                                    if (idx == firstVisible + 1) {
                                        for (fileId in post.content.playbackFileIds()) {
                                            cache.ensure(fileId, DownloadPriority.Prefetch)
                                        }
                                    }
                                }
                            }
                        }

                        CompositionLocalProvider(LocalScrollGate provides scrollGate) {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = contentPadding.calculateBottomPadding(),
                                ),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(
                                    items = displayedList,
                                    key = { it.key },
                                    contentType = { "post" },
                                ) { item ->
                                    val isCenteredState = remember(item.key) {
                                        derivedStateOf { centeredItemKeyState.value == item.key }
                                    }
                                    val post = item.post
                                    val highlighted = highlightedPostKey?.let { (cid, mid) ->
                                        post.chatId == cid && (post.id == mid || mid in post.albumMessageIds)
                                    } == true
                                    CompositionLocalProvider(
                                        LocalIsCenteredItem provides isCenteredState,
                                        LocalIsHighlightedItem provides highlighted,
                                    ) {
                                        PostCard(post = post, interactions = interactions)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (infoSheetVisible) {
        ChannelInfoSheet(
            chatId = chatId,
            backend = backend,
            onDismiss = { infoSheetVisible = false },
            onReport = onReportChannel,
            ignoredChannels = ignoredChannels,
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelTopBar(
    channelTitle: String?,
    channelSubscribers: Int?,
    channelAvatarFileId: Int?,
    channelAvatarThumb: ByteArray?,
    searchActive: Boolean,
    searchQuery: String,
    onBack: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTitleTap: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val barInsets = WindowInsets(0)
    androidx.compose.animation.Crossfade(
        targetState = searchActive,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "channel-bar-search-swap",
    ) { isSearch ->
        if (isSearch) {
            HortayTopBar(
                size = HortayTopBarSize.Compact,
                title = {
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        stringResource(Res.string.timeline_search_in_channel),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onSearchToggle) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Symbol(
                                name = "close",
                                contentDescription = stringResource(Res.string.action_clear),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                windowInsets = barInsets,
            )
        } else {
            val subtitleText = channelSubscribers?.let {
                stringResource(Res.string.timeline_subscribers, formatSubscribers(it))
            }
            ChannelHeaderBar(
                titleText = channelTitle.orEmpty(),
                subtitleText = subtitleText,
                avatar = ChannelHeaderAvatar.Td(
                    fileId = channelAvatarFileId,
                    thumb = channelAvatarThumb,
                    name = channelTitle ?: "?",
                ),
                onBack = onBack,
                onTitleTap = onTitleTap,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onSearchToggle) {
                        Symbol(
                            name = "search",
                            contentDescription = stringResource(Res.string.action_search),
                        )
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty-state composables
// ---------------------------------------------------------------------------

@Composable
private fun ChannelSearchEmpty() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveEmptyHero(
            symbol = "search_off",
            shape = HortayExpressive.FolderSelected,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.timeline_search_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChannelEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveEmptyHero(
            symbol = "forum",
            shape = HortayExpressive.EmptyStateMask,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.channel_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.channel_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Constants local to ChannelScreen
// ---------------------------------------------------------------------------

private const val CHANNEL_PAGINATION_THRESHOLD = 6
private const val CHANNEL_READ_DWELL_MS = 500L
private const val CHANNEL_HIGHLIGHT_DURATION_MS = 2200L
private const val CHANNEL_PREFETCH_DEBOUNCE_MS = 1200L
private const val CHANNEL_COMMENTS_PREFETCH_LIMIT = 1
private const val CHANNEL_PREFETCH_AHEAD = 2
