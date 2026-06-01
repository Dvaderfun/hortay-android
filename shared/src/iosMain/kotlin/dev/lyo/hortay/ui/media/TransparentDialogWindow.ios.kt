package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable

@Composable
internal actual fun TransparentDialogWindow() {
    // iOS Dialog has no opaque window background to strip.
}
