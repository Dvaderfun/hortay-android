package dev.lyo.hortay.ui.web

import androidx.compose.ui.platform.ClipEntry

actual fun ClipEntry.plainText(): String? =
    clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
