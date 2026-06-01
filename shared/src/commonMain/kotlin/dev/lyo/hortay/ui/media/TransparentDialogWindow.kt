package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable

/**
 * Strip the Dialog's platform-default opaque background so the custom
 * black scrim in [FullScreenMediaViewer] can fade cleanly to transparent
 * on swipe-to-dismiss. Android's Dialog window defaults to a white
 * windowBackground + FLAG_DIM_BEHIND; iOS/desktop don't need this.
 *
 * Must be called inside a [androidx.compose.ui.window.Dialog] content block.
 */
@Composable
internal expect fun TransparentDialogWindow()
