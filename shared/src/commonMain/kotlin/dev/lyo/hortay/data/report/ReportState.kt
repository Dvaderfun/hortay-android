package dev.lyo.hortay.data.report

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.ChatId
import dev.lyo.hortay.data.MessageId
import kotlinx.collections.immutable.ImmutableList

/**
 * Server-localised option offered by the TDLib report flow.
 *
 * Plain class (not data class) so the [ByteArray] [id] participates in reference
 * equality — matches the original [TdApi.ReportOption] semantics that the
 * Composable layer was already coded against, and avoids the `@Stable`
 * analysis pitfalls that come with structural ByteArray equality.
 */
@Immutable
class ReportOption(
    val id: ByteArray,
    val label: String,
)

/**
 * States surfaced to the report flow sheet during the multi-step TDLib
 * `ReportChat` interaction.
 *
 * Flow: [Idle] → [Loading] → one of [OptionSelection] / [TextRequired] /
 * [Success] / [Error] / [FloodWait].
 *
 * Sealed interface rather than enum: the data-bearing variants would bloat
 * an enum with nullable fields; sealed interface keeps each variant's
 * contract explicit and Kotlin-exhaustive.
 */
sealed interface ReportState {
    data object Idle : ReportState
    data object Loading : ReportState
    data class OptionSelection(
        val title: String,
        val options: ImmutableList<ReportOption>,
    ) : ReportState
    data class TextRequired(
        val isOptional: Boolean,
    ) : ReportState
    data object Success : ReportState
    /** Generic failure — includes non-flood-wait TDLib errors. */
    data class Error(val message: String) : ReportState
    /** TDLib 420 / 429 FLOOD_WAIT. [retryAfterSeconds] == 0 means "unknown delay". */
    data class FloodWait(val retryAfterSeconds: Int) : ReportState
}

/**
 * Repository-internal step type. Carries the server `optionId` alongside the
 * public-facing [ReportState] so the ViewModel can stash it for the eventual
 * [ReportFlowController.submitText] call without leaking [ByteArray] into the
 * public [ReportState] graph.
 */
data class ReportStep(
    val state: ReportState,
    /** Non-null only when [state] is [ReportState.TextRequired]. */
    val pendingOptionId: ByteArray? = null,
)

/**
 * Platform-agnostic driver for the multi-step TDLib report flow.
 *
 * Android backs this with the TDLib-bound `ReportRepository`. iOS guest-mode
 * provides a no-op stub: guests never reach the report flow because every
 * `Report` action in commonMain UI gates on `LocalGuestReportDelegate` /
 * `isAuthenticated`.
 */
interface ReportFlowController {
    suspend fun start(chatId: ChatId, messageId: MessageId?): ReportStep
    suspend fun selectOption(chatId: ChatId, messageId: MessageId?, option: ReportOption): ReportStep
    suspend fun submitText(chatId: ChatId, messageId: MessageId?, optionId: ByteArray, text: String): ReportStep
}
