@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.lyo.hortay.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.ChatId
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.ui.composables.bars.HortayTopBar
import dev.lyo.hortay.ui.composables.bars.HortayTopBarSize
import dev.lyo.hortay.ui.media.TdAvatar
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.channels_empty_helper
import hortay.shared.generated.resources.channels_empty_title
import hortay.shared.generated.resources.channels_title
import hortay.shared.generated.resources.web_add_channel
import org.jetbrains.compose.resources.stringResource

/**
 * List of channels the user is subscribed to. Data is derived from the same feed as the
 * timeline (each unique chat is a channel), so it stays in sync without a second TDLib query.
 *
 * Rows render through [SegmentedListItem] with [ListItemDefaults.segmentedShapes] — first/last
 * rows get a larger outer radius, inner rows pinch to a tighter corner, and the column gap
 * (`ListItemDefaults.SegmentedGap`) is the spacing that the shape math expects. Mixing this
 * with custom `Arrangement.spacedBy(8.dp)` would visually drift away from the Material 3
 * Expressive segmented-list metric.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelsScreen(
    backend: HortayBackend,
    contentPadding: PaddingValues,
    onChannelClick: (chatId: ChatId) -> Unit,
    suggestionsRepo: dev.lyo.hortay.data.discover.ChannelSuggestionsRepository? = null,
    discovery: dev.lyo.hortay.data.discover.ChannelDiscoveryRepository? = null,
) {
    val posts by backend.feedPosts.collectAsStateWithLifecycle()
    val channels = remember(posts) { aggregate(posts) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var addSheetOpen by remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(Res.string.channels_title),
                size = HortayTopBarSize.Large,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (suggestionsRepo != null && discovery != null) {
                        androidx.compose.material3.IconButton(onClick = { addSheetOpen = true }) {
                            dev.lyo.hortay.ui.icons.Symbol(
                                name = "add",
                                contentDescription = stringResource(Res.string.web_add_channel),
                                tint = MaterialTheme.colorScheme.onSurface,
                                size = 24.dp,
                            )
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (channels.isEmpty()) {
            EmptyChannels(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items = channels, key = { _, it -> it.chatId.value }) { index, ch ->
                    ChannelRow(
                        channel = ch,
                        index = index,
                        count = channels.size,
                        onClick = { onChannelClick(ch.chatId) },
                    )
                }
            }
        }
    }

    if (addSheetOpen && suggestionsRepo != null && discovery != null) {
        dev.lyo.hortay.ui.composables.sheets.AddChannelTdSheet(
            suggestionsRepo = suggestionsRepo,
            discovery = discovery,
            subscribe = { chatId -> backend.joinChat(chatId) },
            locale = androidx.compose.ui.text.intl.Locale.current.language.lowercase(),
            onDismiss = { addSheetOpen = false },
        )
    }
}

// @Immutable required: the ByteArray field defeats Compose's automatic
// stability inference (arrays are mutable references), so without the
// annotation every ChannelRow in a 200-channel LazyColumn re-composes on any
// upstream list mutation — even if the row's own ChannelSummary didn't change.
// The annotation is a contract: ChannelSummary instances are never mutated
// after construction (and `aggregate` builds fresh ones every recomposition,
// so this trivially holds).
@androidx.compose.runtime.Immutable
private data class ChannelSummary(
    val chatId: ChatId,
    val title: String,
    val avatarThumb: ByteArray?,
    val avatarFileId: Int?,
    val lastPostExcerpt: String,
    val lastPostDate: Long,
)

private fun aggregate(posts: List<TimelinePost>): List<ChannelSummary> = posts
    .groupBy { it.chatId }
    .map { (chatId, list) ->
        val anchor = list.maxByOrNull { it.date }!!
        // Personal-author channel mode: each post's senderName / avatar is the admin who
        // wrote it, NOT the channel. We need the channel's own identity here, which lives
        // in [channelContext]. Prefer ANY post in the group whose channelContext is set
        // (or, equivalently, whose own senderName is already the channel) so a channel
        // with multiple posting admins still surfaces as one row with the right name and
        // photo. Falling back to anchor.senderName covers the all-channel-as-sender case
        // where channelContext is null on every post.
        val channelLike = list.firstNotNullOfOrNull { it.channelContext }
        val title = channelLike?.name ?: anchor.senderName
        val thumb = channelLike?.avatarThumb ?: anchor.avatarThumb
        val fileId = channelLike?.avatarFileId ?: anchor.avatarFileId
        ChannelSummary(
            chatId = chatId,
            title = title,
            avatarThumb = thumb,
            avatarFileId = fileId,
            lastPostExcerpt = anchor.content.captionPlain.take(120),
            lastPostDate = anchor.date,
        )
    }
    .sortedByDescending { it.lastPostDate }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChannelRow(
    channel: ChannelSummary,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        leadingContent = {
            TdAvatar(
                name = channel.title,
                thumb = channel.avatarThumb,
                fileId = channel.avatarFileId,
                size = 48.dp,
            )
        },
        supportingContent = if (channel.lastPostExcerpt.isNotBlank()) {
            {
                Text(
                    text = channel.lastPostExcerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else null,
        content = {
            Text(
                text = channel.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun EmptyChannels(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.channels_empty_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.channels_empty_helper),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
