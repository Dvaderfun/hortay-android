// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.data.report

import dev.lyo.hortay.data.ChatId
import dev.lyo.hortay.data.MessageId
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TdRpcException
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.isFloodWaitCode
import kotlinx.collections.immutable.toImmutableList
import dev.lyo.hortay.tdlib.TdApi
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.error_generic

/**
 * Drives the TDLib dynamic ReportChat flow for the authenticated mode.
 *
 * Every call to [start] / [selectOption] / [submitText] maps the raw
 * [TdApi.ReportChatResult] variants to [ReportState] and appends a
 * [ReportLogEntry] when a terminal state is reached (Success, Error, FloodWait).
 * Intermediate states (OptionSelection, TextRequired) are not logged — they carry
 * no outcome information.
 *
 * TDLib contract recap:
 *   ReportChatResultOk            → done
 *   ReportChatResultOptionRequired→ show options list (dynamic, server-localised)
 *   ReportChatResultTextRequired  → show text input; isOptional controls Skip visibility
 *   ReportChatResultMessagesRequired → (rare) ask the user to pick messages;
 *                                  Hortay surfaces this as a generic Error since we
 *                                  have no multi-message selection UI yet.
 */
class ReportRepository(
    private val td: TdSender,
    private val resolver: StringResolver,
    private val log: ReportLogStore,
) : ReportFlowController {
    /**
     * Begin a report against [chatId] / [messageId].
     * Pass an empty option id and empty [text] per TDLib spec for the initial request.
     */
    override suspend fun start(chatId: ChatId, messageId: MessageId?): ReportStep =
        sendReport(chatId, messageId, byteArrayOf(), "")

    /** User selected one of the server-provided options. */
    override suspend fun selectOption(
        chatId: ChatId,
        messageId: MessageId?,
        option: ReportOption,
    ): ReportStep = sendReport(chatId, messageId, option.id, "")

    /** User typed text (or tapped Skip when text is optional). */
    override suspend fun submitText(
        chatId: ChatId,
        messageId: MessageId?,
        optionId: ByteArray,
        text: String,
    ): ReportStep = sendReport(chatId, messageId, optionId, text)

    // -------------------------------------------------------------------------

    private suspend fun sendReport(
        chatId: ChatId,
        messageId: MessageId?,
        optionId: ByteArray,
        text: String,
    ): ReportStep {
        val messageIds = if (messageId != null && messageId.value != 0L) arrayOf(messageId.value)
        else arrayOf()
        val result = runCatching {
            td.send(TdApi.ReportChat(chatId.value, optionId, messageIds, text))
        }
        return result.fold(
            onSuccess = { reportResult -> mapResult(chatId, messageId, reportResult) },
            onFailure = { e -> ReportStep(mapError(chatId, messageId, e)) },
        )
    }

    private suspend fun mapResult(
        chatId: ChatId,
        messageId: MessageId?,
        result: TdApi.ReportChatResult,
    ): ReportStep = when (result) {
        is TdApi.ReportChatResultOk -> {
            logTerminal("tdlib", "ok", chatId, messageId)
            ReportStep(ReportState.Success)
        }
        is TdApi.ReportChatResultOptionRequired ->
            ReportStep(
                ReportState.OptionSelection(
                    title = result.title,
                    options = result.options
                        .map { ReportOption(id = it.id, label = it.text) }
                        .toImmutableList(),
                ),
            )
        is TdApi.ReportChatResultTextRequired ->
            ReportStep(
                state = ReportState.TextRequired(isOptional = result.isOptional),
                pendingOptionId = result.optionId,
            )
        is TdApi.ReportChatResultMessagesRequired -> {
            // We have no multi-message selection UI yet; surface a generic error
            // so the user at least knows the report was attempted.
            val msg = resolver.getString(Res.string.error_generic)
            logTerminal("tdlib", "delegated", chatId, messageId)
            ReportStep(ReportState.Error(msg))
        }
        else -> {
            val msg = resolver.getString(Res.string.error_generic)
            logTerminal("tdlib", "failed", chatId, messageId)
            ReportStep(ReportState.Error(msg))
        }
    }

    private suspend fun mapError(
        chatId: ChatId,
        messageId: MessageId?,
        e: Throwable,
    ): ReportState {
        val tdEx = e as? TdRpcException
        if (tdEx != null && isFloodWaitCode(tdEx.code)) {
            val seconds = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)")
                .find(tdEx.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            logTerminal("tdlib", "failed", chatId, messageId)
            return ReportState.FloodWait(seconds)
        }
        val msg = resolver.getString(Res.string.error_generic)
        logTerminal("tdlib", "failed", chatId, messageId)
        return ReportState.Error(msg)
    }

    private suspend fun logTerminal(
        method: String,
        status: String,
        chatId: ChatId,
        messageId: MessageId?,
    ) {
        log.log(
            ReportLogEntry(
                timestamp = dev.lyo.hortay.nowMs(),
                mode = "auth",
                channelUsername = null,
                chatId = chatId.value,
                messageId = messageId?.value,
                deliveryMethod = method,
                deliveryStatus = status,
            ),
        )
    }
}
