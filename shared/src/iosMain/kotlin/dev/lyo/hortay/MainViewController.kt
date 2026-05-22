package dev.lyo.hortay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.lyo.hortay.data.createPreferencesDataStore
import dev.lyo.hortay.data.web.SubscriptionsStore
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

/**
 * iOS entry point. Called from Swift in `:iosApp`/iosApp/ContentView.swift.
 *
 * v1 guest-mode iOS UI: subscriptions list backed by [SubscriptionsStore] (DataStore
 * Preferences via KMP factory). Real persistence — survives app restarts. Add /
 * remove channel usernames. Next milestones layer in WebFeedSource (HTTP fetch of
 * t.me/s/<u>) and post rendering to reach full feature parity with Android
 * guest-mode.
 */
@Suppress("FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    val subscriptionsStore = remember {
        SubscriptionsStore(createPreferencesDataStore(SubscriptionsStore.FILE_NAME))
    }
    HortayIosTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SubscriptionsScreen(subscriptionsStore)
        }
    }
}

@Composable
private fun HortayIosTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionsScreen(store: SubscriptionsStore) {
    val scope = rememberCoroutineScope()
    val subs by store.subscriptions.collectAsState(initial = kotlinx.collections.immutable.persistentSetOf())
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hortay", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "iOS · guest mode · ${subs.size} channels",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            AddChannelRow(
                value = input,
                onValueChange = {
                    input = it
                    errorMsg = null
                },
                onSubmit = {
                    val username = parseUsername(input)
                    if (username == null) {
                        errorMsg = "Enter @handle, t.me/handle, or just the handle"
                        return@AddChannelRow
                    }
                    scope.launch { store.add(username) }
                    input = ""
                },
            )
            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            if (subs.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(subs.toList(), key = { it }) { username ->
                        SubscriptionRow(
                            username = username,
                            onRemove = { scope.launch { store.remove(username) } },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddChannelRow(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("@channel or t.me/channel") },
            singleLine = true,
        )
        Spacer(Modifier.size(8.dp))
        Button(onClick = onSubmit, enabled = value.isNotBlank()) {
            Text("Add")
        }
    }
}

@Composable
private fun SubscriptionRow(username: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "@$username",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "t.me/$username",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("No channels yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Add a public Telegram channel above to read it without signing in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
