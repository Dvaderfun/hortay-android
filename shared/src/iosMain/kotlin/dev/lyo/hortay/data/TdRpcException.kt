package dev.lyo.hortay.data

/**
 * iOS Throwable → friendly-string adapter. Extracts the raw TDLib token from
 * [TdRpcException.message] and delegates to commonMain's
 * [friendlyAuthErrorMessage]. iOS [message] is already the bare token (no
 * `[code] msg` prefix to strip), but [extractTdErrorCode] is idempotent on
 * clean strings so a unified call site stays safe.
 */
internal fun friendlyAuthErrorMessage(res: StringResolver, throwable: Throwable): String {
    val raw = (throwable as? TdRpcException)?.let { extractTdErrorCode(it.message ?: "") }
        ?: throwable.message.orEmpty()
    return friendlyAuthErrorMessage(res, raw)
}
