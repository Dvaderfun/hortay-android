@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.lyo.hortay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ReactionItem
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.ui.timeline.formatRelative
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

/**
 * iOS entry point. Renders a Twitter-style feed driven off the shared
 * WebFeedSource (commonMain). Until ExoPlayer / Lottie / FlexibleTopAppBar
 * land in the iOS-shareable surface, this stays a parallel renderer that
 * mirrors as much of PostCard's vocabulary as the bare CMP material3 surface
 * + Coil can express:
 *   - rich text from FormattedText spans (bold/italic/code/links/spoiler)
 *   - multi-photo album grids
 *   - reactions row
 *   - forward chip + view count
 *   - relative date strings shared with Android (TimeFormat.formatRelative)
 *   - two tabs: Feed / Channels, with add + manage + refresh actions
 */
private val sharedGraph by lazy { IosAppGraph() }

@Suppress("FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    setSingletonImageLoaderFactory { ctx -> buildImageLoader(ctx) }
    HortayIosTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HortayApp(sharedGraph)
        }
    }
}

private fun buildImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        .crossfade(true)
        .build()

@Composable
private fun HortayIosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

private enum class Tab(val title: String, val symbol: String) {
    Feed("Feed", "🏠"),
    Channels("Channels", "📚"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HortayApp(graph: IosAppGraph) {
    var tab by remember { mutableStateOf(Tab.Feed) }
    var addSheetVisible by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val posts by graph.webFeedSource.posts.collectAsState()
    val subs by graph.subscriptions.subscriptions.collectAsState(
        initial = kotlinx.collections.immutable.persistentSetOf(),
    )
    val channels by graph.webFeedSource.channels.collectAsState()
    val refreshState by graph.webFeedSource.refreshState.collectAsState()

    LaunchedEffect(Unit) { graph.webFeedSource.refreshIfStale() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hortay", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${subs.size} channels · ${posts.size} posts",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    val busy = refreshState is WebFeedSource.RefreshState.Refreshing
                    IconButton(onClick = { scope.launch { graph.webFeedSource.refresh() } }) {
                        Text(if (busy) "…" else "↻", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = { addSheetVisible = true }) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.values().forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.symbol, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Feed -> FeedTab(
                    posts = posts,
                    refreshing = refreshState is WebFeedSource.RefreshState.Refreshing,
                    subsEmpty = subs.isEmpty(),
                    onAdd = { addSheetVisible = true },
                )
                Tab.Channels -> ChannelsTab(
                    channels = channels,
                    onRemove = { username -> scope.launch { graph.subscriptions.remove(username) } },
                    onAdd = { addSheetVisible = true },
                )
            }
        }
    }

    if (addSheetVisible) {
        AddChannelDialog(
            onDismiss = { addSheetVisible = false },
            onAdd = { username ->
                scope.launch { graph.webFeedSource.subscribeAndRefresh(username) }
                addSheetVisible = false
            },
        )
    }
}

@Composable
private fun FeedTab(
    posts: List<TimelinePost>,
    refreshing: Boolean,
    subsEmpty: Boolean,
    onAdd: () -> Unit,
) {
    when {
        subsEmpty -> EmptyState(
            title = "Welcome to Hortay",
            body = "Add a public Telegram channel to read it without signing in.",
            cta = "Add channel",
            onCta = onAdd,
        )
        posts.isEmpty() && refreshing ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        posts.isEmpty() -> EmptyState(
            title = "No posts yet",
            body = "Tap ↻ above to refresh — or wait, the feed polls automatically.",
            cta = null,
            onCta = {},
        )
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(posts, key = { it.id }) { post ->
                PostRow(post)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun ChannelsTab(
    channels: List<dev.lyo.hortay.data.web.ChannelEntry>,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    if (channels.isEmpty()) {
        EmptyState(
            title = "No channels",
            body = "Add a public Telegram channel to start reading.",
            cta = "Add channel",
            onCta = onAdd,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(channels, key = { it.info.username }) { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelAvatar(name = entry.info.title, url = entry.info.avatarUrl, size = 44)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.info.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "@${entry.info.username}" + (entry.info.subscribers?.let { " · $it subscribers" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onRemove(entry.info.username) }) { Text("Hide") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun PostRow(post: TimelinePost) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(name = post.senderName, url = post.avatarUrl, size = 40)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = post.senderName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = post.senderHandle ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = " · " + formatRelative(post.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        post.forwardOrigin?.let { origin ->
            Spacer(Modifier.height(6.dp))
            ForwardChip(origin)
        }
        Spacer(Modifier.height(8.dp))
        PostBody(post.content)
        if (post.reactions.totalCount > 0 || post.views > 0) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                post.reactions.items.take(6).forEach { reaction ->
                    ReactionChip(reaction)
                    Spacer(Modifier.width(6.dp))
                }
                if (post.views > 0) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "👁 ${formatCount(post.views)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelAvatar(name: String, url: String?, size: Int) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ForwardChip(origin: dev.lyo.hortay.data.ForwardOrigin) {
    val label = when (origin) {
        is dev.lyo.hortay.data.ForwardOrigin.Channel -> "Forwarded from ${origin.channelName}"
        is dev.lyo.hortay.data.ForwardOrigin.Chat -> "Forwarded from ${origin.chatName}"
        is dev.lyo.hortay.data.ForwardOrigin.User -> "Forwarded from ${origin.userName}"
        is dev.lyo.hortay.data.ForwardOrigin.HiddenUser -> "Forwarded from ${origin.senderName}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "↪ $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PostBody(content: PostContent) {
    val text = when (content) {
        is PostContent.Text -> content.formatted
        is PostContent.PhotoAlbum -> content.caption
        else -> FormattedText.plain(content.captionPlain)
    }
    if (text.text.isNotBlank()) {
        FormattedTextView(text)
    }
    if (content is PostContent.PhotoAlbum) {
        if (text.text.isNotBlank()) Spacer(Modifier.height(8.dp))
        PhotoAlbumGrid(content.items)
    }
    if (content !is PostContent.Text && content !is PostContent.PhotoAlbum) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "[${content::class.simpleName?.removePrefix("PostContent$")} — open in Telegram]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun FormattedTextView(ft: FormattedText) {
    val uri = LocalUriHandler.current
    val annotated = remember(ft) { buildAnnotated(ft) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 12,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun buildAnnotated(ft: FormattedText): AnnotatedString = buildAnnotatedString {
    append(ft.text)
    ft.spans.forEach { span ->
        if (span.start < 0 || span.end > ft.text.length || span.start >= span.end) return@forEach
        val style = when (val s = span.style) {
            FormattedText.Style.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            FormattedText.Style.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            FormattedText.Style.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
            FormattedText.Style.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            FormattedText.Style.Code -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            is FormattedText.Style.Pre -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            is FormattedText.Style.TextUrl -> SpanStyle(color = Color(0xFF6EA8FE), textDecoration = TextDecoration.Underline)
            FormattedText.Style.Url -> SpanStyle(color = Color(0xFF6EA8FE), textDecoration = TextDecoration.Underline)
            FormattedText.Style.Mention -> SpanStyle(color = Color(0xFF6EA8FE))
            is FormattedText.Style.MentionName -> SpanStyle(color = Color(0xFF6EA8FE))
            FormattedText.Style.Hashtag -> SpanStyle(color = Color(0xFF6EA8FE))
            FormattedText.Style.BotCommand -> SpanStyle(color = Color(0xFF6EA8FE))
            FormattedText.Style.Spoiler -> SpanStyle(background = Color(0xFF222222))
            is FormattedText.Style.CustomEmoji -> SpanStyle()
            FormattedText.Style.BlockQuote -> SpanStyle(color = Color(0xFFB0B0B0))
        }
        addStyle(style, span.start, span.end)
    }
}

@Composable
private fun PhotoAlbumGrid(items: List<AlbumItem>) {
    val urls = items.mapNotNull {
        when (it) {
            is AlbumItem.Photo -> it.media.remoteUrl
            is AlbumItem.Video -> it.media.remoteUrl
            is AlbumItem.Animation -> it.media.remoteUrl
        }
    }
    if (urls.isEmpty()) return
    if (urls.size == 1) {
        AsyncImage(
            model = urls.first(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.2f)),
            contentScale = ContentScale.Crop,
        )
        return
    }
    val cols = if (urls.size == 2) 2 else 3
    val rows = (urls.size + cols - 1) / cols
    val tileHeight = 110
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        modifier = Modifier
            .fillMaxWidth()
            .height((tileHeight * rows + 6 * (rows - 1)).dp),
    ) {
        gridItems(urls) { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .padding(2.dp)
                    .fillMaxWidth()
                    .height(tileHeight.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.2f)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ReactionChip(item: ReactionItem) {
    val label = when (val k = item.kind) {
        is ReactionKind.Emoji -> k.text
        is ReactionKind.CustomEmoji -> "☺"
        ReactionKind.Paid -> "⭐"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(4.dp))
        Text(
            text = formatCount(item.count),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String, cta: String?, onCta: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cta != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCta) { Text(cta) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChannelDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add channel") },
        text = {
            Column {
                Text("Paste a t.me link or @handle.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("@channel or t.me/channel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = input.isNotBlank(),
                onClick = { parseUsername(input)?.let(onAdd) },
            ) { Text("Add") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parseUsername(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    Regex("""^(?:https?://)?t\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{1,31})(?:/\d+)?/?$""")
        .find(trimmed)
        ?.let { return it.groupValues[1].lowercase() }
    val bare = trimmed.removePrefix("@")
    if (bare.matches(Regex("""[A-Za-z][A-Za-z0-9_]{1,31}"""))) return bare.lowercase()
    return null
}

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M".replace(".0M", "M")
    n >= 1_000 -> "${(n / 100) / 10.0}K".replace(".0K", "K")
    else -> n.toString()
}
