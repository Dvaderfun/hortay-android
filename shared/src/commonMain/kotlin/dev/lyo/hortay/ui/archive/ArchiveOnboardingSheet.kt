package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.archive_onboarding_cancel
import hortay.shared.generated.resources.archive_onboarding_enable
import hortay.shared.generated.resources.archive_onboarding_logout
import hortay.shared.generated.resources.archive_onboarding_title
import hortay.shared.generated.resources.archive_onboarding_what
import hortay.shared.generated.resources.archive_onboarding_where
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveOnboardingSheet(
    onDismiss: () -> Unit,
    onEnable: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(24.dp)) {
            Text(
                stringResource(Res.string.archive_onboarding_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(Res.string.archive_onboarding_what),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.archive_onboarding_where),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.archive_onboarding_logout),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.archive_onboarding_cancel))
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = onEnable) {
                    Text(stringResource(Res.string.archive_onboarding_enable))
                }
            }
        }
    }
}
