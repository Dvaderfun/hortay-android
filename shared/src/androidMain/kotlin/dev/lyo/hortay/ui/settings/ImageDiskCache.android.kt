package dev.lyo.hortay.ui.settings

import coil3.SingletonImageLoader
import dev.lyo.hortay.data.PlatformContextHolder

actual fun clearImageDiskCache() {
    val context = PlatformContextHolder.require()
    SingletonImageLoader.get(context).diskCache?.clear()
}
