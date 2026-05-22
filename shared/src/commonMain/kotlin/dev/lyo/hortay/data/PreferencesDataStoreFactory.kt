package dev.lyo.hortay.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.FileSystem
import okio.Path

/**
 * Platform-specific resolver for the on-disk file backing a Preferences DataStore.
 *
 * Android: `app_filesDir/datastore/<name>.preferences_pb`.
 * iOS: `NSDocumentDirectory/datastore/<name>.preferences_pb`.
 *
 * Both end up at a per-app private location that survives upgrades but is wiped on
 * uninstall — matches the Android-only `preferencesDataStore(name)` delegate's
 * default location.
 */
internal expect fun preferencesDataStorePath(name: String): Path

/**
 * Platform-specific Okio filesystem handle. Android: `FileSystem.SYSTEM` over
 * java.io. iOS: `FileSystem.SYSTEM` backed by NSFileManager. Both are read/write
 * with the same path semantics.
 */
internal expect fun preferencesDataStoreFileSystem(): FileSystem

/**
 * KMP factory for a Preferences DataStore at the given logical name. Returned
 * [DataStore] is process-singleton — callers hold the instance for the lifetime
 * of the dependency graph.
 *
 * Implementation uses `PreferenceDataStoreFactory.create` with an Okio-backed
 * `OkioStorage`. The Okio storage variant is the one DataStore 1.2.x officially
 * supports across all KMP targets.
 */
fun createPreferencesDataStore(name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = preferencesDataStoreFileSystem(),
            serializer = androidx.datastore.preferences.core.PreferencesSerializer,
            producePath = { preferencesDataStorePath(name) },
        ),
    )
