package dev.lyo.hortay

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TimelinePost
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

/**
 * iOS entry point. Called from Swift in `:iosApp`/iosApp/ContentView.swift.
 *
 * Phase G7 cut: real guest-mode feed driven off the shared web pipeline
 * (WebFeedSource → WebRepository → WebTelegramClient). Process-singleton
 * [IosAppGraph] holds the pipeline; this composable owns only UI state.
 *
 * Rendering is a slimmer parallel to Android's PostCard (text + first
 * photo + channel header) because the full PostCard tree still pulls
 * ExoPlayer / Lottie / Coil-video which haven't been split into
 * expect/actual yet. Data layer is identical across both platforms.
 */
private val sharedGraph by lazy { IosAppGraph() }

@Suppress("FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    setSingletonImageLoaderFactory { ctx -> buildImageLoader(ctx) }
    HortayIosTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            GuestModeScreen(sharedGraph)
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
    androidx.compose.material3.MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestModeScreen(graph: IosAppGraph) {
    val scope = rememberCoroutineScope()
    val posts by graph.webFeedSource.posts.collectAsState()
    val subs by graph.subscriptions.subscriptions.collectAsState(initial = kotlinx.collections.immutable.persistentSetOf())
    val refreshState by graph.webFeedSource.refreshState.collectAsState()

    var addSheetVisible by remember { mutableStateOf(false) }
    var manageSheetVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        graph.webFeedSource.refreshIfStale()
    }

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
                    IconButton(onClick = { manageSheetVisible = true }) {
                        Text("⚙", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = { scope.launch { graph.webFeedSource.refresh() } }) {
                        val busy = refreshState is dev.lyo.hortay.data.web.WebFeedSource.RefreshState.Refreshing
                        Text(if (busy) "…" else "↻", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = { addSheetVisible = true }) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                subs.isEmpty() -> EmptyState(onAdd = { addSheetVisible = true })
                posts.isEmpty() && refreshState is dev.lyo.hortay.data.web.WebFeedSource.RefreshState.Refreshing ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                posts.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No posts yet. Tap ↻ to refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(posts, key = { it.id }) { post ->
                            PostRow(post)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
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

    if (manageSheetVisible) {
        ManageChannelsDialog(
            usernames = subs.toList(),
            onRemove = { username -> scope.launch { graph.subscriptions.remove(username) } },
            onDismiss = { manageSheetVisible = false },
        )
    }
}

@Composable
private fun PostRow(post: TimelinePost) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (post.avatarUrl != null) {
                AsyncImage(
                    model = post.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        post.senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = post.senderName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = post.senderHandle ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PostBody(post.content)
        if (post.views > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${post.views} views",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PostBody(content: PostContent) {
    when (content) {
        is PostContent.Text -> {
            if (content.formatted.text.isNotBlank()) {
                Text(
                    text = content.formatted.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        is PostContent.PhotoAlbum -> {
            if (content.caption.text.isNotBlank()) {
                Text(
                    text = content.caption.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
            }
            content.items.firstOrNull()?.let { item -> PhotoAlbumPreview(item) }
        }
        else -> {
            Text(
                text = content.captionPlain.ifBlank { "(unsupported content)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PhotoAlbumPreview(item: AlbumItem) {
    val url = when (item) {
        is AlbumItem.Photo -> item.media.remoteUrl
        is AlbumItem.Video -> item.media.remoteUrl
        is AlbumItem.Animation -> item.media.remoteUrl
    } ?: return
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.2f)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Welcome to Hortay", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add a public Telegram channel to read it without signing in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd) { Text("Add channel") }
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
                onClick = {
                    val username = parseUsername(input) ?: return@Button
                    onAdd(username)
                },
            ) { Text("Add") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageChannelsDialog(
    usernames: List<String>,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your channels (${usernames.size})") },
        text = {
            if (usernames.isEmpty()) {
                Text("Nothing subscribed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(usernames, key = { it }) { username ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "@$username",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { onRemove(username) }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
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
