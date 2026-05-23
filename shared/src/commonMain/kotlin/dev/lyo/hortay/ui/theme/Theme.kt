package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

private val LightScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
)

/**
 * Hortay's Material 3 Expressive theme entry point.
 *
 * Layers, in order of how they shape what the user sees:
 *   1. **Color** — Material You dynamic palette on Android 12+ (wallpaper-derived);
 *      brand periwinkle fallback on older devices, iOS, tests, and as the deterministic
 *      baseline for dynamic-color-disabled paths. Platform fork lives in
 *      [dynamicColorSchemeOrNull].
 *   2. **Typography** — Plus Jakarta Sans for display/headline (brand voice on hero
 *      surfaces), Inter for body/label (dense reading). Scale calibrated for the
 *      Expressive type-contrast ratio (display 32 sp+ vs body 14–16 sp).
 *   3. **Shapes** — flat rounded rectangles for dense reading containers
 *      (`HortayShapes`); MaterialShapes presets for hero moments via
 *      `HortayExpressive` (reactions, FAB press, loading, empty states).
 *   4. **Motion** — `MotionScheme.expressive()` swaps the standard duration+easing
 *      animation specs for spring-based physics across every Material component
 *      that reads from `MaterialTheme.motionScheme`. Visible overshoot on selection
 *      transitions, faster damping on scroll-anchored chrome — Google's recommended
 *      default for consumer-facing apps as of M3 Expressive (I/O 2025).
 *
 * Status-bar icon contrast follows the system theme so the chrome reads correctly
 * regardless of which scheme renders below. Hooked through [LocalStatusBarController]
 * — Android wires it from `MainActivity`, iOS from `MainViewController`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HortayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dynamic = if (dynamicColor) dynamicColorSchemeOrNull(darkTheme) else null
    val scheme = dynamic ?: if (darkTheme) DarkScheme else LightScheme

    val statusBar = LocalStatusBarController.current
    SideEffect { statusBar(!darkTheme) }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = HortayTypography,
        shapes = HortayShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
