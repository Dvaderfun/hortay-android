package dev.lyo.hortay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Platform-bridged helpers that fall outside Compose Multiplatform's built-in
 * surface. Kept in one file so the platform-shim count stays low — every
 * helper here is a one-line wrapper over a system service.
 *
 * Available helpers:
 *  - [PlatformClipboard] — copy text to the system clipboard.
 *  - [PlatformShare] — fire the share sheet.
 *  - [PlatformToaster] / [LocalPlatformToaster] — short-lived "Copied!"-style
 *    feedback. Android backs it with `Toast`; iOS surfaces a transient
 *    snackbar from the root view.
 *
 * For opening URLs, use CMP's [androidx.compose.ui.platform.LocalUriHandler] —
 * it already does the right thing on both platforms.
 */
expect object PlatformClipboard {
    /**
     * Place [text] on the system clipboard under [label]. Android < 13 used to
     * surface a "Copied" toast separately; from 13+ the system shows its own
     * confirmation popup, so call sites that want a toast on old Android +
     * iOS can layer [LocalPlatformToaster] on top of the copy.
     */
    fun writeText(label: String, text: String)
}

expect object PlatformShare {
    /** Open the share sheet with plain text. Optional [subject] populates email targets. */
    fun shareText(text: String, subject: String? = null)

    /** Convenience wrapper that shares a URL. Equivalent to [shareText] but the share
     *  sheet may pick handler apps differently when it knows the payload is a URL. */
    fun shareUrl(url: String)
}

/** Thin "show a transient text overlay" surface — Android Toast / iOS in-app banner. */
interface PlatformToaster {
    fun show(text: String, durationMs: Long = 2000L)
}

/**
 * Provided once at the top of the composition. Android wires a Toast-backed
 * implementation; iOS wires a fallback that posts an attributed string to the
 * root SwiftUI view (no-op for now — guest-mode flows don't surface toasts
 * yet, and iOS users see system-level "Copied" feedback for clipboard ops).
 */
val LocalPlatformToaster = staticCompositionLocalOf<PlatformToaster> {
    object : PlatformToaster {
        override fun show(text: String, durationMs: Long) {
            // Default no-op — silently dropped if no platform toaster is provided.
            // Tests + previews get this default; real screens see the wired impl.
        }
    }
}

/**
 * Convenience for screens that want a "do nothing if no toaster is wired"
 * helper. Reads [LocalPlatformToaster] and returns its `show` callback as a
 * lambda for stable capture inside event handlers.
 */
@Composable
fun rememberToaster(): (String) -> Unit {
    val toaster = LocalPlatformToaster.current
    return { text -> toaster.show(text) }
}
