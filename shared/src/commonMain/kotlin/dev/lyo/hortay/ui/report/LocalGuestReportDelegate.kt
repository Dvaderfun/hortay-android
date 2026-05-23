// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Architecture: delegation to Telegram moderation when no TDLib session exists

package dev.lyo.hortay.ui.report

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Outcome of attempting to delegate a guest-mode report.
 *
 *  - [OpenedTelegram]  — handed the report to an installed Telegram client.
 *  - [OpenedWeb]       — fell back to a Custom Tab / external browser.
 *  - [OpenedEmail]     — fell back to mailto: abuse@telegram.org.
 *  - [AllFailed]       — every delegation surface refused; UI should show the
 *                        manual-instructions dialog.
 */
enum class GuestReportOutcome {
    OpenedTelegram,
    OpenedWeb,
    OpenedEmail,
    AllFailed,
}

/**
 * Lambda slot provided at the top of the composition. Android wires the
 * `GuestReportDelegator` impl from `AppGraph`. iOS guest mode (no Telegram
 * client to launch, no Custom Tabs API) wires a stub that returns
 * [GuestReportOutcome.AllFailed] so the manual-instructions dialog surfaces.
 */
typealias GuestReportDelegate = (channelUsername: String?, postId: Long?) -> GuestReportOutcome

val LocalGuestReportDelegate = staticCompositionLocalOf<GuestReportDelegate> {
    { _, _ -> GuestReportOutcome.AllFailed }
}
