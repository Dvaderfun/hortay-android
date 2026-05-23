package dev.lyo.hortay.ui.theme

import androidx.compose.ui.text.font.FontFamily

// v1 iOS shim: system sans-serif until we bundle Inter / Plus Jakarta Sans
// .ttf files in commonMain/composeResources/font/ (Phase A5). The brand still
// reads consistently because every Composable derives its weight from the
// shared MaterialTheme.typography spec.
actual val DisplayFontFamily: FontFamily = FontFamily.SansSerif
actual val BodyFontFamily: FontFamily = FontFamily.SansSerif
