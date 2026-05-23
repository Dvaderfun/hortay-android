package dev.lyo.hortay

import dev.lyo.hortay.data.PlatformContextHolder
import okio.Path
import okio.Path.Companion.toPath

actual fun applicationFilesPath(name: String): Path {
    val context = PlatformContextHolder.require()
    return context.filesDir.resolve(name).absolutePath.toPath()
}
