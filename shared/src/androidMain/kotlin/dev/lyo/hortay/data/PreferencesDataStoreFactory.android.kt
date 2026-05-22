package dev.lyo.hortay.data

import android.content.Context
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Android-side bootstrap: HortayApp.onCreate calls [PlatformContextHolder.init]
 * before AppGraph spins up so DataStore-backed stores can resolve their on-disk
 * file path without each store accepting a Context constructor param.
 *
 * `applicationContext` is a process-singleton — no leak risk.
 */
object PlatformContextHolder {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun require(): Context = appContext
        ?: error("PlatformContextHolder not initialized. Call init() from HortayApp.onCreate before any AppGraph access.")
}

internal actual fun preferencesDataStorePath(name: String): Path {
    val context = PlatformContextHolder.require()
    return context.filesDir.resolve("datastore/$name.preferences_pb").absolutePath.toPath()
}

internal actual fun preferencesDataStoreFileSystem(): FileSystem = FileSystem.SYSTEM
