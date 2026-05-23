package dev.lyo.hortay.ui.settings

import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lyo.hortay.data.AutoDownloadCategory
import dev.lyo.hortay.data.PlatformContextHolder

/**
 * Cellular-only Data-Saver detection. Wi-Fi never triggers the OS toggle, and a
 * roaming connection is still cellular for restriction purposes — query in both
 * cases. Two re-check sources:
 *   • Lifecycle ON_RESUME — covers the canonical "user dipped into Android
 *     Settings → Data Saver, came back to Hortay" path. Cheap to re-query.
 *   • [ConnectivityManager.OnRestrictBackgroundChangedListener] does not exist
 *     as a public API; the supported mechanism is the
 *     `ACTION_RESTRICT_BACKGROUND_CHANGED` implicit broadcast, which manifest-
 *     declared receivers can't get on Oreo+. Foreground re-check on resume
 *     covers our use case (the banner is only seen on the foreground).
 */
@Composable
actual fun rememberDataSaverActive(category: AutoDownloadCategory): State<Boolean> {
    val state = remember(category) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(category, lifecycleOwner) {
        if (category == AutoDownloadCategory.Wifi) {
            state.value = false
            return@DisposableEffect onDispose { }
        }
        val context = PlatformContextHolder.require()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun recompute() {
            state.value = cm?.let {
                runCatching {
                    it.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
                }.getOrDefault(false)
            } ?: false
        }
        recompute()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recompute()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * Surface the OS toggle directly so the user can flip it without hunting
 * through Android Settings. `ACTION_DATA_USAGE_SETTINGS` is the documented
 * entry point — works back to API 26 (matches our minSdk). Wrapped in
 * `runCatching` because some Samsung One UI builds throw
 * `ActivityNotFoundException` on this exact intent on locked-down enterprise
 * devices; falling through silently is correct (the banner stays visible,
 * the user can still toggle in Settings → Connections → Data Usage manually).
 */
actual fun openOsDataUsageSettings() {
    val context = PlatformContextHolder.require()
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
