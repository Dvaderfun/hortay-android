package dev.lyo.hortay.ui.settings

/**
 * Wipe Coil's image disk cache. Used by SettingsScreen's "Clear cache"
 * action: TDLib's storage clear only touches `tdlib-files/`, leaving Coil's
 * cache (channel avatars, web-mode photos) untouched. Calling both gives the
 * user the freed-disk number they expect.
 *
 * iOS guest mode currently shares the same Coil cache, so the actual impl
 * does the same call on both platforms — but iOS guest mode doesn't surface
 * the SettingsScreen "clear cache" row, so the call only fires on Android in
 * practice today.
 */
expect fun clearImageDiskCache()
