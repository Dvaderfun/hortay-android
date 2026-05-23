package dev.lyo.hortay

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.lyo.hortay.data.PlatformContextHolder

/**
 * Android actuals for [PlatformClipboard] + [PlatformShare]. Resolve the
 * application context via [PlatformContextHolder] so call sites don't need a
 * Context handle (same pattern as the DataStore factory).
 */
actual object PlatformClipboard {
    actual fun writeText(label: String, text: String) {
        val context = PlatformContextHolder.require()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

actual object PlatformShare {
    actual fun shareText(text: String, subject: String?) {
        val context = PlatformContextHolder.require()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }

    actual fun shareUrl(url: String) = shareText(url)
}

/** Toast-backed [PlatformToaster]. Provided through `LocalPlatformToaster` in `HortayApp`. */
class AndroidToaster(private val context: Context) : PlatformToaster {
    override fun show(text: String, durationMs: Long) {
        val length = if (durationMs > Toast.LENGTH_SHORT.toLong() * 1000L) {
            Toast.LENGTH_LONG
        } else Toast.LENGTH_SHORT
        Toast.makeText(context.applicationContext, text, length).show()
    }
}

actual fun systemAnimatorDurationScale(): Float = ValueAnimator.getDurationScale()
