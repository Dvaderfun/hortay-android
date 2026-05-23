package dev.lyo.hortay

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.countryCode

actual fun currentLanguageTag(): String {
    val locale = NSLocale.currentLocale
    val lang = locale.languageCode
    val country = locale.countryCode
    return if (!country.isNullOrEmpty()) "$lang-$country" else lang
}
