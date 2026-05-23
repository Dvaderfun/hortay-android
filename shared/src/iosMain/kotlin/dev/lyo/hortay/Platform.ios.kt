@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.lyo.hortay

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController

/**
 * iOS actuals. Clipboard goes to `UIPasteboard.generalPasteboard`; share fires
 * a `UIActivityViewController` from whichever view controller is currently
 * presenting (walks the chain from `keyWindow.rootViewController` past
 * presented sheets so an open modal hosts the share, not a stale root).
 */
actual object PlatformClipboard {
    actual fun writeText(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
    }
}

actual object PlatformShare {
    actual fun shareText(text: String, subject: String?) {
        val controller = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        topMostViewController()?.presentViewController(controller, animated = true, completion = null)
    }

    actual fun shareUrl(url: String) = shareText(url)
}

private fun topMostViewController(): UIViewController? {
    var current = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (current?.presentedViewController != null) {
        current = current.presentedViewController
    }
    return current
}

actual fun systemAnimatorDurationScale(): Float =
    if (UIAccessibilityIsReduceMotionEnabled()) 0f else 1f
