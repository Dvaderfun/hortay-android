package dev.lyo.hortay.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * Two display + body families. Android bundles Plus Jakarta Sans + Inter as variable
 * TTFs in `androidMain/res/font/` (no runtime provider, no Play Services — see
 * `Fonts.android.kt`); iOS falls back to the system sans-serif until the .ttf files
 * are bundled cross-platform (deferred — CMP's `Res.font` can't pin a variable-font
 * weight axis, so iOS needs platform-specific axis control).
 *
 * The expect-val shape lets every Composable that references these names work
 * unchanged in commonMain — only the platform actual decides where the glyphs
 * come from.
 */
expect val DisplayFontFamily: FontFamily
expect val BodyFontFamily: FontFamily
