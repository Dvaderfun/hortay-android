package dev.lyo.hortay

import platform.Foundation.NSBundle

/**
 * Populate the commonMain [AppConfig] singleton from the iOS app's `Info.plist`
 * before [dev.lyo.hortay.di.initKoin] runs. Mirrors Android's
 * `HortayApp.onCreate` BuildConfig → AppConfig bridge.
 *
 * Expected `Info.plist` keys (case-sensitive; missing keys leave the field at
 * its default of 0 / empty string, which surfaces as a friendly
 * `auth_err_app_misconfigured` once the auth flow first calls TDLib):
 *
 *   - `TelegramApiId`              `Number` — from <https://my.telegram.org>
 *   - `TelegramApiHash`            `String` — from <https://my.telegram.org>
 *   - `HortayChildSafetyPolicyUrl` `String` (optional)
 *   - `HortayPrivacyPolicyUrl`     `String` (optional)
 *
 * For local development the recommended workflow is an `xcconfig` file
 * (gitignored) that defines the build-setting values; `Info.plist` then
 * pulls them in via `$(TELEGRAM_API_ID)` substitution. This keeps secrets out
 * of git the same way Android's `local.properties` does.
 *
 * Called from `MainViewController.kt` through the same `lazy { … }` delegate
 * that wraps `initKoin()` — guarantees this runs exactly once, before any
 * Koin lookup.
 */
internal fun populateAppConfig() {
    val info = NSBundle.mainBundle.infoDictionary
    AppConfig.telegramApiId = ((info?.get(KEY_API_ID) as? Number)?.toInt())
        ?: (info?.get(KEY_API_ID) as? String)?.toIntOrNull()
        ?: 0
    AppConfig.telegramApiHash = (info?.get(KEY_API_HASH) as? String).orEmpty()
    AppConfig.childSafetyPolicyUrl = (info?.get(KEY_CHILD_SAFETY_URL) as? String).orEmpty()
    AppConfig.privacyPolicyUrl = (info?.get(KEY_PRIVACY_URL) as? String).orEmpty()
    AppConfig.versionName = (info?.get(KEY_BUNDLE_SHORT_VERSION) as? String).orEmpty()
    AppConfig.versionCode = (info?.get(KEY_BUNDLE_VERSION) as? String)?.toIntOrNull() ?: 0
    // `debug` stays at its compile-time default `false`. K/N has no
    // build-config equivalent of Android's `BuildConfig.DEBUG` — wire one via
    // a Kotlin compiler arg (`-Pdebug=true`) into a generated source file if
    // we ever need a different verbosity per build configuration.
}

private const val KEY_API_ID = "TelegramApiId"
private const val KEY_API_HASH = "TelegramApiHash"
private const val KEY_CHILD_SAFETY_URL = "HortayChildSafetyPolicyUrl"
private const val KEY_PRIVACY_URL = "HortayPrivacyPolicyUrl"
private const val KEY_BUNDLE_SHORT_VERSION = "CFBundleShortVersionString"
private const val KEY_BUNDLE_VERSION = "CFBundleVersion"
