package dev.lyo.hortay.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Paints the system status bar icons light or dark from a Compose theme.
 *
 * Lambda slot — `light = true` ⇒ dark icons on light background; `light = false`
 * ⇒ light icons on dark background. Provided by the platform entry point:
 *   - Android: `MainActivity` wires it via `WindowCompat.getInsetsController(window, view)`.
 *   - iOS: `MainViewController` wires it via the hosting `UIViewController`'s
 *     `setNeedsStatusBarAppearanceUpdate`.
 *
 * Default is a no-op so previews / tests don't crash; in production the
 * platform always overrides via [CompositionLocalProvider].
 */
val LocalStatusBarController = staticCompositionLocalOf<(Boolean) -> Unit> {
    { _ -> /* no-op default; platform entry overrides */ }
}
