@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.ChatId
import dev.lyo.hortay.data.ComposeResourcesStringResolver
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.MessageId
import dev.lyo.hortay.data.UserId
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.composables.sheets.ReportFlowSheet
import dev.lyo.hortay.ui.composables.dialogs.ChatInvitePreviewDialog
import dev.lyo.hortay.ui.composables.sheets.UserProfileSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.report_success

/**
 * Three modal surfaces that live at MainScaffold scope rather than inside the
 * Scaffold content lambda so they outlive the tab/channel that triggered them
 * and survive rotation:
 *
 *  - [ChatInvitePreviewDialog]: pending invite-link confirmation, stored on
 *    [linkDialogs] rather than in a local rememberSaveable because TDLib's
 *    `CheckChatInviteLink` is suspending and runs on the app scope — a rotation
 *    between the user tapping the link and the preview arriving would otherwise
 *    drop the dialog on the floor.
 *
 *  - [ReportFlowSheet]: in-app reporting flow (auth mode). Rendered as a
 *    ModalBottomSheet here so it outlives the PostCard that triggered it and
 *    survives tab/channel changes while the user is mid-flow.
 *
 *  - [UserProfileSheet]: shared surface for every "tap a user" trigger
 *    (comment header, personal-author PostCard, forward-from-user chip, in-text
 *    mention with a userId).
 */
@Composable
internal fun MainScaffoldDialogs(
    backend: HortayBackend,
    linkDialogs: LinkDialogState,
    userMessages: UserMessageBus,
    scope: CoroutineScope,
    pendingUserId: UserId?,
    onUserSheetDismiss: () -> Unit,
    onPushChannel: (chatId: ChatId, scrollTo: MessageId?) -> Unit,
) {
    val res = remember { ComposeResourcesStringResolver() }
    val pendingInvitePreview by linkDialogs.invitePreview.collectAsStateWithLifecycle()
    val pendingReport by backend.reportDialogs.target.collectAsStateWithLifecycle()

    pendingInvitePreview?.let { preview ->
        ChatInvitePreviewDialog(
            preview = preview,
            onConfirm = {
                linkDialogs.dismissInvitePreview()
                scope.launch {
                    val joinedId = backend.joinByInvite(preview.inviteLink)
                    if (joinedId != null) {
                        onPushChannel(ChatId(joinedId), null)
                    }
                }
            },
            onDismiss = { linkDialogs.dismissInvitePreview() },
        )
    }

    pendingReport?.let { target ->
        ReportFlowSheet(
            chatId = target.chatId,
            messageId = target.messageId,
            openToken = target.token,
            channelUsername = null,
            onDismiss = { success ->
                backend.reportDialogs.close()
                // Surface a confirmation snackbar via the existing UserMessageBus
                // (Severity.Info, not Error — the report succeeded). Manual
                // dismissals skip this path so the user only sees feedback when
                // something actually happened.
                if (success) {
                    userMessages.post(
                        res.getString(Res.string.report_success),
                        UserMessageBus.Severity.Info,
                    )
                }
            },
            reportController = backend.reportController,
            explainerStore = backend.reportExplainerStore,
        )
    }

    pendingUserId?.let { userId ->
        UserProfileSheet(
            userId = userId,
            backend = backend,
            onDismiss = onUserSheetDismiss,
            onOpenChannel = { chatId -> onPushChannel(chatId, null) },
        )
    }
}
