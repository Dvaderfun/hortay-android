package dev.lyo.hortay.ui.web

import androidx.compose.ui.platform.ClipEntry
import platform.UIKit.UIPasteboard

/**
 * iOS reads through [UIPasteboard.generalPasteboard] — the ClipEntry on iOS
 * wraps a native NSItemProvider but does not expose a public text accessor at
 * this CMP version; falling back to the system pasteboard is the documented
 * approach for clipboard-driven smart-paste flows.
 */
@Suppress("unused")
actual fun ClipEntry.plainText(): String? =
    UIPasteboard.generalPasteboard.string
