package dev.lyo.hortay.data.web

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
 * Persistent list of channel usernames the user has subscribed to in anonymous mode.
 *
 * Phase F migration: refactored from `class SubscriptionsStore(context: Context)` to
 * `class SubscriptionsStore(dataStore: DataStore<Preferences>)`. KMP DataStore factory
 * (createPreferencesDataStore) injects the platform-resolved file from AppGraph /
 * IosAppGraph, so this store itself is platform-agnostic and moves to commonMain.
 *
 * Why a separate store from TDLib's chat list:
 *   - Anonymous mode has no Telegram session, so there's no server-side notion of
 *     "your channels". We track the user's intent locally.
 *   - When a user signs in (Phase 2 step F: anonymous → authenticated migration),
 *     this set becomes the input list for the auto-subscribe flow.
 *
 * Storage shape: `Set<String>` of bare usernames (no `@`, no URL). Bare so write
 * callers don't have to remember the canonical form — callers normalize via
 * `parseUsernameFromInput` before write. Realistic upper bound ~200 channels.
 *
 * `ImmutableSet` exposed to consumers so Compose stability inference treats the
 * collection as a value rather than a mutable reference.
 */
class SubscriptionsStore(
    private val dataStore: DataStore<Preferences>,
) {

    val subscriptions: Flow<ImmutableSet<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SUBSCRIPTIONS]?.toPersistentSet() ?: persistentSetOf()
    }

    suspend fun add(username: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SUBSCRIPTIONS] ?: emptySet()
            prefs[KEY_SUBSCRIPTIONS] = current + username
        }
    }

    suspend fun remove(username: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SUBSCRIPTIONS] ?: emptySet()
            prefs[KEY_SUBSCRIPTIONS] = current - username
        }
    }

    suspend fun snapshot(): Set<String> = dataStore.data.let { flow ->
        var first: Set<String> = emptySet()
        flow.collect { prefs ->
            first = prefs[KEY_SUBSCRIPTIONS] ?: emptySet()
            return@collect
        }
        first
    }

    companion object {
        private val KEY_SUBSCRIPTIONS = stringSetPreferencesKey("guest_subscriptions")

        const val FILE_NAME = "guest_subscriptions"
    }
}
