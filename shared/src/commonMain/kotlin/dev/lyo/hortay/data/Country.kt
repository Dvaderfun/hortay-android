package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * One country row as the picker sees it. The TDLib payload (`TdApi.CountryInfo`)
 * carries an array of dialing codes per country (the US/Caribbean NANP cluster
 * shares "+1" with a slew of area-code-specific entries) — we keep the first as
 * the display dial code which matches what official Telegram does, but the
 * whole array is preserved so a future `GetPhoneNumberInfo` lookup can identify
 * every variant.
 */
@Immutable
data class Country(
    val iso: String,
    val name: String,
    val dialCode: String,
    val flag: String,
    val allDialCodes: ImmutableList<String>,
    /**
     * True for the synthetic "Інша країна" row — selecting it unlocks the dial-code
     * field for free typing so a user with an exotic carrier code can punch in
     * whatever they need without us shipping the entire E.164 plan in resources.
     */
    val isCustom: Boolean = false,
)
