package dev.lyo.hortay.data

/**
 * RPC failure from TDLib. Both the Android JNI path ([TdClient.TdException])
 * and the iOS tdjson path ([dev.lyo.hortay.tdlib.TypedTdClient]) throw this
 * type so commonMain code can catch one class regardless of platform.
 *
 * [code] is the MTProto numeric code (400 / 401 / 406 / 420 / 429 / 500 …).
 * [message] is the raw SCREAMING_SNAKE token (`PHONE_NUMBER_INVALID`,
 * `FLOOD_WAIT_42`, …).
 */
open class TdRpcException(val code: Int, message: String) : RuntimeException(message)

/**
 * True when [code] represents a Telegram per-method rate limit. TDLib reports
 * two distinct codes depending on which protocol layer answered:
 *   - 420 — legacy MTProto (`FLOOD_WAIT_42`)
 *   - 429 — newer translation (`Too Many Requests: retry after 42`)
 * Both observed in the field — treat as equivalent.
 */
fun isFloodWaitCode(code: Int): Boolean = code == 420 || code == 429

/**
 * Pulls the retry-after second count out of a TDLib flood-wait error message.
 * Matches both wire formats — `FLOOD_WAIT_<n>` (420 / MTProto) and
 * `retry after <n>` (429 / translated layer, verbose form
 * "Too Many Requests: retry after <n>"). Returns null when the message carries
 * no parseable count. Mirrors the Android `TdClient` regex so both platforms
 * arm the flood gate identically.
 */
fun floodWaitSeconds(message: String?): Int? =
    message?.let { FLOOD_WAIT_SECONDS_RX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

private val FLOOD_WAIT_SECONDS_RX = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)")

/**
 * True when this throwable is TDLib's documented silent-no-op sentinel
 * (code 406). Callers with optimistic UI (poll vote, reaction toggle) treat
 * a 406 outcome as success so the authoritative update is what flips the
 * chip — never a snackbar that TDLib explicitly forbids.
 */
fun Throwable?.isTdSilent(): Boolean =
    (this as? TdRpcException)?.code == 406
