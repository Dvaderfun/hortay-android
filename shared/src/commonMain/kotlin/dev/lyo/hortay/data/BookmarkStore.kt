package dev.lyo.hortay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent set of bookmarked posts, keyed by `chatId/messageId`.
 *
 * Backed by a Preferences DataStore — async I/O, survives process death, no
 * main-thread blocking. The set is exposed as a [Flow] so bookmarks toggle
 * live across the UI without an event bus.
 *
 * KMP: constructor takes the platform-provided [DataStore] handle from
 * [createPreferencesDataStore] — same pattern every other commonMain store
 * uses (IgnoredChannelsStore, GuestModeStore, MigrationStore).
 */
class BookmarkStore(private val dataStore: DataStore<Preferences>) {

    val bookmarks: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY] ?: emptySet()
    }

    suspend fun toggle(post: TimelinePost) {
        val key = post.bookmarkKey()
        dataStore.edit { prefs ->
            val current = prefs[KEY] ?: emptySet()
            prefs[KEY] = if (key in current) current - key else current + key
        }
    }

    companion object {
        const val FILE_NAME: String = "bookmarks"
        private val KEY = stringSetPreferencesKey("bookmarks")
    }
}

fun TimelinePost.bookmarkKey(): String = "${chatId.value}/${id.value}"
