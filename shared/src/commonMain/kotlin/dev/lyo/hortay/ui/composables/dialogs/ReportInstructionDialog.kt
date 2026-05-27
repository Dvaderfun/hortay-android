// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.ui.composables.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.report_about_dialog_ok
import hortay.shared.generated.resources.report_guest_instruction_body
import hortay.shared.generated.resources.report_guest_instruction_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shown after guest-mode delegation opens Telegram or a Web tab.
 * Tells the user how to complete the report in the external surface.
 */
@Composable
fun ReportInstructionDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.report_about_dialog_ok))
            }
        },
        title = { Text(stringResource(Res.string.report_guest_instruction_title)) },
        text = { Text(stringResource(Res.string.report_guest_instruction_body)) },
    )
}
