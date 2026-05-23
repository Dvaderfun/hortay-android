package dev.lyo.hortay.ui.settings

/**
 * iOS guest mode doesn't surface authenticated Settings → Clear cache,
 * so the actual impl stays a no-op until Phase II wires Coil's cache
 * teardown into the iOS surface.
 */
actual fun clearImageDiskCache() {}
