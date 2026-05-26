package dev.lyo.hortay.data

/**
 * Android Throwable → friendly-string adapter. Extracts the raw TDLib token
 * (PHONE_NUMBER_INVALID, FLOOD_WAIT_42…) from [TdRpcException]'s
 * `[code] msg` format and delegates to commonMain's [friendlyAuthErrorMessage].
 *
 * Non-TdRpcException throwables fall back to `throwable.message` — covers the
 * occasional `IllegalStateException` from `runCatching` boundaries.
 */
internal fun friendlyAuthErrorMessage(res: StringResolver, throwable: Throwable): String {
    val raw = (throwable as? TdRpcException)?.let { extractTdErrorCode(it.message ?: "") }
        ?: throwable.message.orEmpty()
    return friendlyAuthErrorMessage(res, raw)
}
