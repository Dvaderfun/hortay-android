package dev.lyo.hortay.data

import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.auth_err_app_misconfigured
import hortay.shared.generated.resources.auth_err_code_expired
import hortay.shared.generated.resources.auth_err_code_invalid
import hortay.shared.generated.resources.auth_err_generic
import hortay.shared.generated.resources.auth_err_password_invalid
import hortay.shared.generated.resources.auth_err_password_recovery_na
import hortay.shared.generated.resources.auth_err_password_too_fresh
import hortay.shared.generated.resources.auth_err_phone_banned
import hortay.shared.generated.resources.auth_err_phone_flood
import hortay.shared.generated.resources.auth_err_phone_invalid
import hortay.shared.generated.resources.auth_err_phone_occupied
import hortay.shared.generated.resources.auth_err_session_password_needed
import hortay.shared.generated.resources.auth_err_step_failed
import hortay.shared.generated.resources.auth_err_token_invalid
import hortay.shared.generated.resources.auth_err_too_many_generic
import hortay.shared.generated.resources.auth_err_too_many_with_time
import hortay.shared.generated.resources.duration_hours
import hortay.shared.generated.resources.duration_minutes
import hortay.shared.generated.resources.duration_seconds_short

/**
 * TDLib speaks SCREAMING_SNAKE error codes (PHONE_NUMBER_INVALID, FLOOD_WAIT_42,
 * PASSWORD_HASH_INVALID…). It deliberately leaves localisation to the client because the
 * codes are stable contract; the messages aren't. We translate the ones a real user can
 * actually act on, and fall back to a neutral phrase for the rest so we never blast
 * `[400] PHONE_NUMBER_BANNED` at someone's screen.
 *
 * Strings live in `composeResources/values{,-uk}/strings.xml`; we resolve through
 * [StringResolver] rather than hold a localized lookup table here so locale changes
 * propagate via the standard resource-config path.
 *
 * Keep the mapping ordered by likelihood (cheap wins early). Anything that contains a
 * numeric tail (FLOOD_WAIT_X) needs prefix matching; everything else is exact.
 *
 * Pure (rawCode → friendly) — platform-specific Throwable adapters live in
 * `androidMain` / `iosArm64Main` and call into this helper after extracting the raw
 * code from their respective RPC-exception types.
 */
internal fun friendlyAuthErrorMessage(res: StringResolver, rawCode: String): String = when {
    rawCode.startsWith("FLOOD_WAIT") -> {
        val seconds = rawCode.substringAfter("FLOOD_WAIT_", "").toIntOrNull()
        if (seconds != null) {
            res.getString(Res.string.auth_err_too_many_with_time, humaniseSeconds(res, seconds))
        } else {
            res.getString(Res.string.auth_err_too_many_generic)
        }
    }
    rawCode == "PHONE_NUMBER_INVALID" -> res.getString(Res.string.auth_err_phone_invalid)
    rawCode == "PHONE_NUMBER_BANNED" -> res.getString(Res.string.auth_err_phone_banned)
    rawCode == "PHONE_NUMBER_FLOOD" -> res.getString(Res.string.auth_err_phone_flood)
    rawCode == "PHONE_NUMBER_OCCUPIED" -> res.getString(Res.string.auth_err_phone_occupied)
    rawCode == "PHONE_CODE_INVALID" || rawCode == "PHONE_CODE_EMPTY" ->
        res.getString(Res.string.auth_err_code_invalid)
    rawCode == "PHONE_CODE_EXPIRED" -> res.getString(Res.string.auth_err_code_expired)
    rawCode == "PASSWORD_HASH_INVALID" -> res.getString(Res.string.auth_err_password_invalid)
    rawCode == "PASSWORD_TOO_FRESH" -> res.getString(Res.string.auth_err_password_too_fresh)
    rawCode == "PASSWORD_RECOVERY_NA" -> res.getString(Res.string.auth_err_password_recovery_na)
    rawCode == "SESSION_PASSWORD_NEEDED" -> res.getString(Res.string.auth_err_session_password_needed)
    rawCode == "API_ID_INVALID" || rawCode == "API_ID_PUBLISHED_FLOOD" ->
        res.getString(Res.string.auth_err_app_misconfigured)
    rawCode == "ACCESS_TOKEN_INVALID" -> res.getString(Res.string.auth_err_token_invalid)
    rawCode.isBlank() -> res.getString(Res.string.auth_err_generic)
    // Unknown code — show a neutral message but keep the raw code in parens for bug
    // reports. The user can't act on PHONE_MIGRATE_2 directly but they can copy it
    // into a support message if it ever happens.
    else -> res.getString(Res.string.auth_err_step_failed, rawCode)
}

/**
 * Strip a TDLib `[code] FOO_BAR` exception-message prefix down to just `FOO_BAR`.
 * Idempotent on already-clean strings — pass any RPC message through this before
 * handing it to [friendlyAuthErrorMessage] so the raw-token lookups match.
 */
internal fun extractTdErrorCode(full: String): String {
    val closeBracket = full.indexOf(']')
    return if (closeBracket >= 0 && full.startsWith('[')) {
        full.substring(closeBracket + 1).trim()
    } else {
        full.trim()
    }
}

private fun humaniseSeconds(res: StringResolver, total: Int): String = when {
    total < 60 -> res.getString(Res.string.duration_seconds_short, total)
    total < 3600 -> {
        val m = total / 60
        res.getQuantityString(Res.plurals.duration_minutes, m, m)
    }
    else -> {
        val h = total / 3600
        res.getQuantityString(Res.plurals.duration_hours, h, h)
    }
}
