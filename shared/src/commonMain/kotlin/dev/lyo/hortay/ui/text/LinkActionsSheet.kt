@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.PlatformClipboard
import dev.lyo.hortay.PlatformShare
import dev.lyo.hortay.rememberToaster
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.link_action_copy
import hortay.shared.generated.resources.link_action_open
import hortay.shared.generated.resources.link_action_share
import hortay.shared.generated.resources.link_copied_toast
import org.jetbrains.compose.resources.stringResource

/**
 * Telegram-style action sheet for a long-pressed link. Three rows rendered as
 * a single M3 Expressive segmented group (`SegmentedListItem` +
 * `segmentedShapes`) — same idiom Settings / AutoDownload use for grouped
 * settings rows, so the visual vocabulary stays consistent across every
 * list-of-actions surface in the app.
 *
 * Actions:
 *   - Open      → routes through [LocalUriHandler]; internal Telegram URIs
 *                  land in Hortay via HortayUriHandler, generic URLs fall
 *                  through to the system browser.
 *   - Copy link → system clipboard via [PlatformClipboard]. Android 12L and
 *                  older surface a Toast via the platform toaster; Android
 *                  13+ shows the system's own confirmation chip so a manual
 *                  toast there would double-confirm. iOS shows its own
 *                  clipboard confirmation banner.
 *   - Share     → [PlatformShare.shareUrl] — system share sheet, URL-only
 *                  payload (no excerpt — that's PostActions.share's job).
 *
 * The sheet header renders the full URL via [prettyLinkPreview] (scheme +
 * bold host + plain tail, truncated past 96 chars). Bolding the host puts
 * the user's attention on the security-relevant part of the URL — the same
 * anti-phishing affordance the confirmation dialog uses.
 */
@Composable
fun LinkActionsSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val toast = rememberToaster()
    val copiedMessage = stringResource(Res.string.link_copied_toast)
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val pretty = remember(url) { prettyLinkPreview(url) }

    val dismiss: () -> Unit = {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Text(
                text = pretty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                LinkAction(
                    index = 0,
                    count = 3,
                    label = stringResource(Res.string.link_action_open),
                    icon = "open_in_new",
                    onClick = {
                        runCatching { uriHandler.openUri(url) }
                        dismiss()
                    },
                )
                LinkAction(
                    index = 1,
                    count = 3,
                    label = stringResource(Res.string.link_action_copy),
                    icon = "content_copy",
                    onClick = {
                        PlatformClipboard.writeText(label = "link", text = url)
                        toast(copiedMessage)
                        dismiss()
                    },
                )
                LinkAction(
                    index = 2,
                    count = 3,
                    label = stringResource(Res.string.link_action_share),
                    icon = "share",
                    onClick = {
                        PlatformShare.shareUrl(url)
                        dismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun LinkAction(index: Int, count: Int, label: String, icon: String, onClick: () -> Unit) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        leadingContent = { Symbol(name = icon) },
        content = { Text(label) },
    )
}
