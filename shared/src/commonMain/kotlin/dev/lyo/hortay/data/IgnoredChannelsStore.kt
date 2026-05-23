package dev.lyo.hortay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-curated set of channel chat ids whose posts should be hidden from the
 * main timeline. Survives across cold starts and across the auth ↔ guest
 * routing flip — the user's intent ("don't show me posts from this channel
 * in the home feed") is a preference, not session state.
 */
class IgnoredChannelsStore(
    private val dataStore: DataStore<Preferences>,
) {

    val ignored: Flow<ImmutableSet<Long>> = dataStore.data.map { prefs ->
        prefs[KEY_IGNORED]?.mapNotNull { it.toLongOrNull() }?.toPersistentSet() ?: persistentSetOf()
    }

    suspend fun add(chatId: Long) {
        if (chatId == 0L) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED] ?: emptySet()
            prefs[KEY_IGNORED] = current + chatId.toString()
        }
    }

    suspend fun remove(chatId: Long) {
        if (chatId == 0L) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED] ?: emptySet()
            prefs[KEY_IGNORED] = current - chatId.toString()
        }
    }

    suspend fun toggle(chatId: Long): Boolean {
        if (chatId == 0L) return false
        var resultingState = false
        dataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED] ?: emptySet()
            val key = chatId.toString()
            if (key in current) {
                prefs[KEY_IGNORED] = current - key
                resultingState = false
            } else {
                prefs[KEY_IGNORED] = current + key
                resultingState = true
            }
        }
        return resultingState
    }

    companion object {
        const val FILE_NAME = "ignored_channels"
        private val KEY_IGNORED = stringSetPreferencesKey("ignored_channel_ids")
    }
}
