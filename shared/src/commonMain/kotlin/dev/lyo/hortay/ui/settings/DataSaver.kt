package dev.lyo.hortay.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import dev.lyo.hortay.data.AutoDownloadCategory

/**
 * True when the OS-level Data Saver toggle (or carrier-equivalent) is currently
 * restricting background data for the [category]'s network type. Android reads
 * [android.net.ConnectivityManager.restrictBackgroundStatus]; iOS returns false
 * (Apple's "Low Data Mode" lives at the connection level, not as a global
 * setting we can query without a NWPathMonitor handshake — defer to Phase II).
 *
 * Re-evaluated on lifecycle resume so the banner flips off as soon as the user
 * comes back from the OS settings page.
 */
@Composable
expect fun rememberDataSaverActive(category: AutoDownloadCategory): State<Boolean>

/**
 * Open the OS-level data-usage settings page. Android fires
 * `Intent.ACTION_DATA_USAGE_SETTINGS`; iOS opens the Settings root via
 * `UIApplication.openURL("App-Prefs:")` once the iOS port is wired.
 */
expect fun openOsDataUsageSettings()
