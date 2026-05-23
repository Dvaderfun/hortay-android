package dev.lyo.hortay.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Per-channel ViewModel created (and keyed) by [ChannelScreen] on entry.
 * Each chatId gets its own instance via `viewModel(key = "channel:$chatId")`,
 * so the all-feed [TimelineViewModel] and every channel view have
 * independent state machines.
 *
 * Design decisions that differ from [TimelineViewModel]:
 *
 *   - **Single [data] state.** Channel posts and the "still loading"
 *     bit live in one [ChannelData] union exposed as one StateFlow.
 *     Compose can never observe an inconsistent (posts, loading) pair
 *     across the asynchronous gap between two StateFlow updates — the
 *     previous design's race is structurally absent. See [ChannelData]
 *     KDoc for the full rationale.
 *
 *   - No pending-new / high-water semantics. A single-channel view shows
 *     ALL posts for that channel including real-time arrivals — there is
 *     no Twitter-style "X new posts" pill concept when the user is
 *     already inside the channel.
 *
 *   - [paginationLoading] coalesces rapid near-bottom scroll events so
 *     [HortayBackend.loadOlder] is never called while a previous load
 *     for this channel is still in flight.
 *
 *   - [channelTitle] is derived reactively from the channel slice
 *     (preferred, same canonical-identity rule as the old TimelineScreen
 *     filter bar) with a suspend [HortayBackend.chatTitle] fallback
 *     for channels not yet in the merged feed.
 *     [channelSubscribers] uses [HortayBackend.channelSubscribers]
 *     which is a one-shot TDLib cache hit in steady state.
 *
 *   - Search is fully owned here — 300 ms debounce on [searchQuery],
 *     scoped to [chatId] via [HortayBackend.searchInChannel]. Results
 *     live in [searchResults]; the UI reads [searchActive] to decide
 *     which list to render.
 *
 * No need for `restoreFromSnapshot` / `refreshIfStale` here —
 * [HortayBackend.feedPosts] is the single upstream and is populated by
 * [TimelineViewModel] via the feed bootstrap. [HortayBackend.loadChannelHistory]
 * gives the deep per-channel slice the feed does not have on cold start.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ChannelViewModel(
    private val backend: HortayBackend,
    private val bookmarks: BookmarkStore,
    val chatId: Long,
    val scrollToMessageId: Long?,
) : ViewModel() {

    // Cold flow that filters the global posts stream down to this channel.
    private val channelSlice: Flow<PersistentList<TimelinePost>> = backend.feedPosts
        .map { all -> all.filter { it.chatId == chatId }.toPersistentList() }
        .distinctUntilChanged()

    // Single source of truth for channel data state. Compose observes only
    // [data]; `posts` and `historyLoading` are derived from the snapshot
    // it returns (see [ChannelScreen]). That makes inconsistent
    // (posts, loading) pairs literally not representable.
    private val _data = MutableStateFlow<ChannelData>(initialData())
    val data: StateFlow<ChannelData> = _data.asStateFlow()

    private fun initialData(): ChannelData =
        if (backend.hasWarmChannelHistory(chatId)) {
            ChannelData.Loaded(channelSliceNow())
        } else {
            ChannelData.Loading
        }

    private fun channelSliceNow(): PersistentList<TimelinePost> =
        backend.feedPosts.value.filter { it.chatId == chatId }.toPersistentList()

    // Deep-link around-load attempt flag. Starts false; flipped to true
    // by the init block after [HortayBackend.loadHistoryAround] resolves
    // (or the 1500 ms grace elapses). Consumed by [buildChannelUiState].
    private val _attemptedAround = MutableStateFlow(false)
    val attemptedAround: StateFlow<Boolean> = _attemptedAround.asStateFlow()

    // Pagination single-flight guard.
    private val _paginationLoading = MutableStateFlow(false)
    val paginationLoading: StateFlow<Boolean> = _paginationLoading.asStateFlow()

    // PTR in-flight state.
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // Channel header: title resolved from the post stream first, suspend
    // chatTitle fallback for channels not yet in the merged feed.
    private val _channelTitle = MutableStateFlow<String?>(null)
    val channelTitle: StateFlow<String?> = _channelTitle.asStateFlow()

    // Subscriber count: synchronous seed from the in-memory mirror so
    // [ChannelHeaderBar]'s subtitle paints with the count on its first
    // frame; cold-cache fallback runs only when the synchronous read
    // returns null.
    private val _channelSubscribers = MutableStateFlow(backend.channelSubscribersCached(chatId))
    val channelSubscribers: StateFlow<Int?> = _channelSubscribers.asStateFlow()

    // Channel avatar: minithumb / fileId pair for the top-bar TdAvatar.
    private val _channelAvatarFileId = MutableStateFlow<Int?>(null)
    val channelAvatarFileId: StateFlow<Int?> = _channelAvatarFileId.asStateFlow()

    private val _channelAvatarThumb = MutableStateFlow<ByteArray?>(null)
    val channelAvatarThumb: StateFlow<ByteArray?> = _channelAvatarThumb.asStateFlow()

    // Bookmark set forwarded from the shared [BookmarkStore].
    val bookmarkedKeys: StateFlow<Set<String>> = bookmarks.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    // --- Search state ---

    private val _searchActive = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = _searchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Results: debounced + flatMapped so a new keystroke cancels the
    // in-flight RPC. Empty when search is inactive or query is blank.
    val searchResults: StateFlow<List<TimelinePost>> = _searchQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { query ->
            flow {
                emit(
                    if (_searchActive.value && query.isNotBlank()) {
                        backend.searchInChannel(chatId, query.trim())
                    } else {
                        emptyList()
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        // OpenChat for the lifetime of this VM, with the history loads
        // issued inside the open window. Per TDLib (Aliaksei Levin /
        // [tdlib/td#2937] + the [TdApi.OpenChat] docstring):
        //
        //   "Informs TDLib that the chat is opened by the user. Many
        //    useful activities depend on the chat being opened or closed
        //    (e.g., in supergroups and channels all updates are received
        //    only for opened chats)."
        //
        // Without [HortayBackend.openChat], [TdApi.GetChatHistory] for a
        // channel the user hasn't viewed before is served from a cold
        // local cache and returns an empty list even though the server
        // has posts — the symptom was an "empty channel" hero on first
        // entry. [CloseChat] runs under [NonCancellable] so a fast
        // back-press still flushes the close.
        viewModelScope.launch {
            backend.openChat(chatId)
            try {
                // Cold-load: transition Loading → Loaded atomically once
                // [loadChannelHistory] returns. Skipped when we're
                // already Loaded (warm re-entry from [initialData]).
                if (_data.value is ChannelData.Loading) {
                    launch {
                        try {
                            backend.loadChannelHistory(chatId)
                        } finally {
                            _data.value = ChannelData.Loaded(channelSliceNow())
                        }
                    }
                }

                // Live-ingest: keep [_data] in sync with the channel
                // slice for the lifetime of the VM. Drops emissions
                // while we're in Loading so the screen doesn't paint a
                // partial slice before the cold-load transition.
                launch {
                    channelSlice.collect { slice ->
                        val current = _data.value
                        if (current is ChannelData.Loaded && current.posts != slice) {
                            _data.value = ChannelData.Loaded(slice)
                        }
                    }
                }

                // Deep-link around-load: if the caller supplied a
                // scrollToMessageId, check whether the target is already
                // in the global feed slice. If not, issue
                // loadHistoryAround exactly once.
                if (scrollToMessageId != null) {
                    launch {
                        val initialMatch = backend.feedPosts.value.any { p ->
                            p.chatId == chatId &&
                                (p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds)
                        }
                        if (!initialMatch) {
                            val landed = runCatching {
                                backend.loadHistoryAround(chatId, scrollToMessageId)
                            }.getOrDefault(false)
                            if (landed) {
                                withTimeoutOrNull(1_500L) {
                                    backend.feedPosts.first { all ->
                                        all.any { p ->
                                            p.chatId == chatId &&
                                                (p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds)
                                        }
                                    }
                                }
                            }
                        }
                        _attemptedAround.value = true
                    }
                }
                // Hold OpenChat for the screen's lifetime — TDLib keeps
                // streaming updates until CloseChat.
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { backend.closeChat(chatId) }
            }
        }

        // Channel title + avatar from the post stream, suspend fallback.
        viewModelScope.launch {
            val anchor = backend.feedPosts.value.firstOrNull { it.chatId == chatId }
            val titleFromPosts = anchor?.let { it.channelContext?.name ?: it.senderName }
            if (titleFromPosts != null) {
                _channelTitle.value = titleFromPosts
            } else {
                _channelTitle.value = backend.chatTitle(chatId)
            }
            val avatarFromPosts = anchor?.let {
                (it.channelContext?.avatarFileId ?: it.avatarFileId) to
                    (it.channelContext?.avatarThumb ?: it.avatarThumb)
            }
            if (avatarFromPosts != null && (avatarFromPosts.first != null || avatarFromPosts.second != null)) {
                _channelAvatarFileId.value = avatarFromPosts.first
                _channelAvatarThumb.value = avatarFromPosts.second
            } else {
                val cached = backend.chatAvatar(chatId)
                if (cached != null) {
                    _channelAvatarFileId.value = cached.first
                    _channelAvatarThumb.value = cached.second
                }
            }
        }
        viewModelScope.launch {
            // Keep title AND avatar up-to-date as posts arrive.
            channelSlice.collect { channelPosts ->
                val anchor = channelPosts.firstOrNull() ?: return@collect
                val channelLike = channelPosts.firstNotNullOfOrNull { it.channelContext }
                val resolvedName = channelLike?.name ?: anchor.senderName
                if (resolvedName != _channelTitle.value) {
                    _channelTitle.value = resolvedName
                }
                val resolvedFileId = channelLike?.avatarFileId
                    ?: anchor.channelContext?.avatarFileId
                    ?: anchor.avatarFileId
                val resolvedThumb = channelLike?.avatarThumb
                    ?: anchor.channelContext?.avatarThumb
                    ?: anchor.avatarThumb
                if (resolvedFileId != _channelAvatarFileId.value) {
                    _channelAvatarFileId.value = resolvedFileId
                }
                if (resolvedThumb !== _channelAvatarThumb.value) {
                    _channelAvatarThumb.value = resolvedThumb
                }
            }
        }
        // Subscriber count cold-cache fallback for never-seen channels.
        if (_channelSubscribers.value == null) {
            viewModelScope.launch {
                _channelSubscribers.value = backend.channelSubscribers(chatId)
            }
        }
    }

    /** Pull-to-refresh for the channel view. */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                backend.loadChannelHistory(chatId)
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Paginate older history for this channel. Guards against concurrent
     * calls — if a [loadOlder] is already in flight, the next call is a
     * no-op.
     */
    fun loadOlderIfPossible() {
        if (_paginationLoading.value) return
        viewModelScope.launch {
            _paginationLoading.value = true
            try {
                backend.loadOlder(chatId)
            } finally {
                _paginationLoading.value = false
            }
        }
    }

    fun setSearchActive(active: Boolean) {
        _searchActive.value = active
        if (!active) _searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleBookmark(post: TimelinePost) {
        viewModelScope.launch { bookmarks.toggle(post) }
    }

    suspend fun viewMessages(chatId: Long, messageIds: List<Long>) {
        backend.viewMessages(chatId, messageIds)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How long after the last keystroke before issuing a
         * [HortayBackend.searchInChannel] RPC. Matches
         * [TimelineScreen.SEARCH_DEBOUNCE_MS] for consistent UX cadence.
         */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
