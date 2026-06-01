package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.archive.ArchivedContent
import dev.lyo.hortay.data.archive.ArchivedMediaRef
import dev.lyo.hortay.data.archive.ArchivedMediaStore
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import dev.lyo.hortay.data.archive.diff.PostDiff
import dev.lyo.hortay.ui.archive.components.DiffText
import dev.lyo.hortay.ui.archive.components.RevisionMediaPreview
import dev.lyo.hortay.ui.archive.components.RevisionTimeline
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.revision_baseline_caveat
import hortay.shared.generated.resources.revision_empty
import hortay.shared.generated.resources.revision_go_to_current
import hortay.shared.generated.resources.revision_open_in_telegram
import hortay.shared.generated.resources.revision_sheet_title
import hortay.shared.generated.resources.revision_sheet_title_deleted
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.compose.resources.stringResource

/**
 * Bottom sheet showing the edit history for a single message. When [revisions] contains a `DELETED`
 * row, the sheet renders in deleted-mode (no "Go to current" button). The diff between two
 * revisions uses [PostDiff]; for the first row the sheet shows the text with a baseline caveat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostRevisionSheet(
    revisions: ImmutableList<PostSnapshot>,
    onDismiss: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onGoToCurrent: (() -> Unit)? = null,
    mediaStore: ArchivedMediaStore? = null,
) {
    if (revisions.isEmpty()) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Text(
                stringResource(Res.string.revision_empty),
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    val isDeleted = revisions.any { it.kind == SnapshotKind.DELETED }
    var selectedIndex by remember { mutableStateOf(revisions.lastIndex) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(
                    if (isDeleted) Res.string.revision_sheet_title_deleted else Res.string.revision_sheet_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            RevisionTimeline(
                timestamps = revisions.map { it.seenAtMs }.toPersistentList(),
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
            )
            mediaRefOf(revisions[selectedIndex])?.let { media ->
                RevisionMediaPreview(media = media, mediaStore = mediaStore)
            }
            if (selectedIndex > 0) {
                val older = revisions[selectedIndex - 1]
                val newer = revisions[selectedIndex]
                val oldText = textOf(older)
                val newText = textOf(newer)
                val diff = remember(oldText, newText) { PostDiff.compute(oldText, newText) }
                DiffText(diff)
            } else {
                val current = revisions[selectedIndex]
                Text(textOf(current), modifier = Modifier.padding(8.dp))
                if (current.editedAtMs != null) {
                    Text(
                        stringResource(Res.string.revision_baseline_caveat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!isDeleted && onGoToCurrent != null) {
                    TextButton(onClick = onGoToCurrent) {
                        Text(stringResource(Res.string.revision_go_to_current))
                    }
                } else {
                    Spacer(Modifier)
                }
                TextButton(onClick = onOpenInTelegram) {
                    Text(stringResource(Res.string.revision_open_in_telegram))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun textOf(snapshot: PostSnapshot): String = when (val c = snapshot.content) {
    is ArchivedContent.Tdlib -> c.meta.text
    is ArchivedContent.Web -> c.textPreview
}

private fun mediaRefOf(snapshot: PostSnapshot): ArchivedMediaRef? =
    when (val c = snapshot.content) {
        is ArchivedContent.Tdlib -> c.meta.mediaRef
        is ArchivedContent.Web -> null
    }
