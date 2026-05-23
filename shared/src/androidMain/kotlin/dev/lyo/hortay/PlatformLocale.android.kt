package dev.lyo.hortay

import java.util.Locale

actual fun currentLanguageTag(): String = Locale.getDefault().toLanguageTag()
