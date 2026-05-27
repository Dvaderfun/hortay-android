@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lyo.hortay.ui.composables.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.ChatInvitePreview
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.action_cancel
import hortay.shared.generated.resources.invite_dialog_join
import hortay.shared.generated.resources.invite_dialog_subscribers
import hortay.shared.generated.resources.invite_dialog_title
import hortay.shared.generated.resources.invite_dialog_unknown_chat
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation dialog for a Telegram channel invite link. Surfaces the resolved title
 * and member count from `CheckChatInviteLink` so the user knows what they're about to
 * join — same flow Telegram-Android shows before joining via an invite link.
 *
 * Only used for [dev.lyo.hortay.data.InviteLinkKind.Channel]. Group invites take a
 * different scaffold path (snackbar + hand-off to Telegram client) because Hortay
 * has no group surface.
 */
@Composable
fun ChatInvitePreviewDialog(
    preview: ChatInvitePreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.invite_dialog_title)) },
        text = {
            Column(modifier = Modifier) {
                Text(
                    text = preview.title.ifBlank { stringResource(Res.string.invite_dialog_unknown_chat) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (preview.memberCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pluralStringResource(
                            Res.plurals.invite_dialog_subscribers,
                            preview.memberCount,
                            preview.memberCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.invite_dialog_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
