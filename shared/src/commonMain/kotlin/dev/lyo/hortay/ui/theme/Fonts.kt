package dev.lyo.hortay.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * Two display + body families. Android pulls Plus Jakarta Sans + Inter from
 * Google Fonts via Play Services at runtime; iOS falls back to the system
 * sans-serif until Phase A5 (bundle the .ttf files in commonMain/composeResources/font/).
 *
 * The expect-val shape lets every Composable that references these names work
 * unchanged in commonMain — only the platform actual decides where the glyphs
 * come from.
 */
expect val DisplayFontFamily: FontFamily
expect val BodyFontFamily: FontFamily
