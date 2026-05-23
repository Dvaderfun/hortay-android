package dev.lyo.hortay.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Per-app language picker surface. Android impl reads / writes through
 * `LocaleStore` (LocaleManager on API 33+, SharedPrefs + Activity.recreate on
 * older versions). iOS impl uses `UserDefaults["AppleLanguages"]` (Phase II).
 *
 * Read by Settings → "App language". When [tag] is null the app follows the
 * system locale; otherwise the BCP-47 tag (`"en"`, `"uk"`) pins the choice.
 *
 * [apply] is the only write path: it persists the choice AND triggers the
 * platform-specific UI-refresh (Activity recreate on legacy Android, no-op on
 * Tiramisu+ where the system handles recreation).
 */
interface LanguagePicker {
    fun current(): String?
    fun apply(tag: String?)
}

/**
 * Provided by [MainActivity] / [MainViewController]. Default is a stub that
 * returns null / no-ops — keeps Compose previews + tests from needing a real
 * impl wired up.
 */
val LocalLanguagePicker = staticCompositionLocalOf<LanguagePicker> {
    object : LanguagePicker {
        override fun current(): String? = null
        override fun apply(tag: String?) {}
    }
}
