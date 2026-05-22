package dev.lyo.hortay.data

import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual fun preferencesDataStorePath(name: String): Path {
    val documentDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    val root = requireNotNull(documentDirectory?.path) {
        "Cannot resolve NSDocumentDirectory for DataStore path"
    }
    return "$root/datastore/$name.preferences_pb".toPath()
}

internal actual fun preferencesDataStoreFileSystem(): FileSystem = FileSystem.SYSTEM
