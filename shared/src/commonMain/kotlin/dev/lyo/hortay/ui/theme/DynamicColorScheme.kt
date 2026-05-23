package dev.lyo.hortay.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Material You wallpaper-derived dynamic color scheme. Android 12+: returns
 * `dynamic{Dark,Light}ColorScheme(LocalContext.current)`. iOS / Android < 12:
 * returns null so the caller falls back to the brand palette.
 */
@Composable
expect fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme?
