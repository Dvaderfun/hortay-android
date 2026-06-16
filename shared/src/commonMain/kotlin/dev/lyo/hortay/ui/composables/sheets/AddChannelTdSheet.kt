@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.composables.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.ChatId
import dev.lyo.hortay.data.discover.ChannelCardData
import dev.lyo.hortay.data.discover.ChannelDiscoveryRepository
import dev.lyo.hortay.data.discover.ChannelSuggestionsRepository
import dev.lyo.hortay.data.discover.DiscoverChannel
import dev.lyo.hortay.data.discover.SuggestedGroup
import dev.lyo.hortay.ui.discover.ChannelDiscoverRow
import dev.lyo.hortay.ui.discover.discoverSuggestions
import dev.lyo.hortay.ui.timeline.formatSubscribers
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.discover_no_results
import hortay.shared.generated.resources.discover_search_hint
import hortay.shared.generated.resources.discover_subscribe
import hortay.shared.generated.resources.discover_subscribed
import hortay.shared.generated.resources.web_add_channel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.stringResource

/**
 * Authenticated-mode counterpart of [AddChannelSheet]. Shares the curated catalog
 * ([ChannelSuggestionsRepository]) and the row chrome ([ChannelDiscoverRow] /
 * [discoverSuggestions]) with guest mode, but every network touch goes through
 * TDLib ([ChannelDiscoveryRepository]) — authenticated mode never calls t.me/s
 * (the privacy boundary).
 *
 * Two surfaces share one scroll container:
 *  - empty query → curated suggestions, hydrated + member-filtered via TDLib;
 *  - non-empty query → live `SearchPublicChats` results, debounced.
 *
 * Subscribing goes through [subscribe] (the host wires `HortayBackend.joinChat`);
 * the channel then flows into the feed through TDLib's update stream. Subscribed
 * rows are dropped optimistically so the tap reads as "done" without waiting for
 * a membership re-resolve.
 */
@Composable
fun AddChannelTdSheet(
    suggestionsRepo: ChannelSuggestionsRepository,
    discovery: ChannelDiscoveryRepository,
    subscribe: suspend (ChatId) -> Unit,
    locale: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }

    // Curated catalog for this locale.
    val groups by produceState(initialValue = emptyList<SuggestedGroup>(), locale) {
        value = suggestionsRepo.groups(locale)
    }

    // Live TDLib hydration of the curated handles (avatar / title / subscribers /
    // membership). Throttled inside [ChannelDiscoveryRepository]; the Semaphore here
    // just bounds how many resolve coroutines are outstanding at once.
    val hydrated = remember { mutableStateMapOf<String, ChannelCardData>() }
    // Channels the user just subscribed to from this sheet — hidden immediately so
    // the tap reads as committed.
    val justSubscribed = remember { mutableStateMapOf<String, Unit>() }

    LaunchedHydration(groups, hydrated, discovery)

    val visibleGroups by remember(groups) {
        derivedStateOf {
            groups.mapNotNull { g ->
                val remaining = g.channels.filter {
                    val key = it.username.lowercase()
                    hydrated[key]?.isMember != true && key !in justSubscribed
                }
                if (remaining.isEmpty()) null else g.copy(channels = remaining.toImmutableList())
            }
        }
    }

    // Debounced public-channel search.
    val searchResults = remember { mutableStateListOf<DiscoverChannel>() }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            searchResults.clear()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        val results = discovery.search(q)
        searchResults.clear()
        searchResults.addAll(results)
        searching = false
    }

    fun subscribeByUsername(username: String) {
        scope.launch {
            val card = discovery.resolve(username) ?: return@launch
            subscribe(ChatId(card.chatId))
            justSubscribed[username.lowercase()] = Unit
        }
    }

    fun subscribeResult(channel: DiscoverChannel) {
        scope.launch {
            subscribe(ChatId(channel.chatId))
            channel.username?.let { justSubscribed[it.lowercase()] = Unit }
        }
    }

    // Hoisted: stringResource() is @Composable but the LazyListScope content lambda
    // (and discoverSuggestions args evaluated in it) is not.
    val subscribeLabel = stringResource(Res.string.discover_subscribe)
    val subscribedLabel = stringResource(Res.string.discover_subscribed)

    // Bound the LazyColumn height so it never receives an infinite max constraint
    // from the sheet's wrap-content Column (would crash). KMP stand-in for the
    // Android-only LocalConfiguration.screenHeightDp: window container size → dp.
    val windowSizePx = LocalWindowInfo.current.containerSize
    val maxSheetHeight = with(LocalDensity.current) { (windowSizePx.height * 0.82f).toDp() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title", contentType = "title") {
                Text(
                    text = stringResource(Res.string.web_add_channel),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item(key = "search", contentType = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.discover_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (query.trim().length >= 2) {
                if (searching && searchResults.isEmpty()) {
                    item(key = "searching") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            LoadingIndicator(Modifier.size(28.dp))
                        }
                    }
                } else if (searchResults.isEmpty()) {
                    item(key = "no_results") {
                        Text(
                            text = stringResource(Res.string.discover_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(
                    items = searchResults,
                    key = { "s_${it.chatId}" },
                    contentType = { "discover_row" },
                ) { channel ->
                    val subscribed = channel.isMember ||
                        channel.username?.lowercase() in justSubscribed
                    ChannelDiscoverRow(
                        name = channel.title,
                        handle = channel.username?.let { "@$it" } ?: "",
                        subscribers = channel.subscribers?.let { formatSubscribers(it) },
                        description = null,
                        avatarThumb = channel.avatarThumb,
                        avatarFileId = channel.avatarFileId,
                        avatarUrl = null,
                        actionLabel = if (subscribed) subscribedLabel else subscribeLabel,
                        actionEnabled = !subscribed,
                        onAction = { subscribeResult(channel) },
                    )
                }
            } else {
                discoverSuggestions(
                    groups = visibleGroups,
                    hydrated = hydrated,
                    addLabel = subscribeLabel,
                    onAdd = { username -> subscribeByUsername(username) },
                )
            }
        }
    }
}

/**
 * Eager, bounded hydration of the curated handles via TDLib. Split out so the sheet
 * body stays readable; re-runs whenever [groups] changes and skips handles already
 * in [hydrated].
 */
@Composable
private fun LaunchedHydration(
    groups: List<SuggestedGroup>,
    hydrated: SnapshotStateMap<String, ChannelCardData>,
    discovery: ChannelDiscoveryRepository,
) {
    LaunchedEffect(groups) {
        val names = groups.flatMap { it.channels }.map { it.username }.distinct()
        val gate = Semaphore(3)
        coroutineScope {
            names.forEach { u ->
                if (hydrated.containsKey(u.lowercase())) return@forEach
                launch {
                    gate.withPermit {
                        val card = discovery.resolve(u) ?: return@withPermit
                        hydrated[u.lowercase()] = ChannelCardData(
                            title = card.title,
                            subscribersText = card.subscribers?.let { formatSubscribers(it) },
                            avatarThumb = card.avatarThumb,
                            avatarFileId = card.avatarFileId,
                            isMember = card.isMember,
                        )
                    }
                }
            }
        }
    }
}
