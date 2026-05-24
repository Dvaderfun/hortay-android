package dev.lyo.hortay.ui.web

import androidx.compose.ui.platform.ClipEntry

/**
 * Best-effort plain-text read from a [ClipEntry]. Android pulls the first item
 * via the native `ClipData`; iOS reads through `UIPasteboard.string` (when
 * available). Returns null when the clip carries something other than text
 * (image, file URI), or when no clipboard content is present.
 *
 * Currently used by the guest-mode AddChannelSheet to auto-paste a Telegram
 * handle from the clipboard — anything that isn't a recognisable handle is
 * silently dropped at the regex check, so a wrong inference here is harmless.
 */
expect fun ClipEntry.plainText(): String?
