package dev.lyo.hortay

import android.content.pm.ApplicationInfo

actual val isDebugBuild: Boolean by lazy {
    val ctx = dev.lyo.hortay.data.PlatformContextHolder.require()
    (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
