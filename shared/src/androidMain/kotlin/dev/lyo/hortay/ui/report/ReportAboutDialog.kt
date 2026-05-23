// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.ui.report

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.report_about_dialog_body
import hortay.shared.generated.resources.report_about_dialog_ok
import hortay.shared.generated.resources.report_about_dialog_title
import org.jetbrains.compose.resources.stringResource

/**
 * One-time explainer shown before the first report in either mode.
 * Persisted via [dev.lyo.hortay.data.report.ReportExplainerStore]; once dismissed
 * it never shows again.
 *
 * Copy is intentionally factual: "reported to Telegram moderators" — we do not
 * claim Hortay reviews anything.
 */
@Composable
fun ReportAboutDialog(
    onDismiss: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.report_about_dialog_ok))
            }
        },
        title = { Text(stringResource(Res.string.report_about_dialog_title)) },
        text = { Text(stringResource(Res.string.report_about_dialog_body)) },
    )
}
