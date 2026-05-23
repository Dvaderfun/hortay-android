package dev.lyo.hortay.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.lyo.hortay.data.AutoDownloadCategory

/** iOS guest mode has no equivalent global toggle — banner stays hidden. */
@Composable
actual fun rememberDataSaverActive(category: AutoDownloadCategory): State<Boolean> =
    remember { mutableStateOf(false) }

/** No-op on iOS until the AutoDownload surface is exposed in guest mode. */
actual fun openOsDataUsageSettings() {}
