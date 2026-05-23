package dev.lyo.hortay.data.web

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the user's choice to use the app *without* Telegram authentication.
 *
 * State machine:
 *   - false (default) — first launch, OR user has signed in / requested sign-in.
 *     [MainActivity] routes auth.Ready → TDLib UI, anything else → [AuthScreen].
 *   - true — user explicitly tapped "Continue without sign-in" on the auth landing.
 *     [MainActivity] routes through to [WebTimelineScreen] regardless of TDLib's
 *     auth state. The user can flip back from the in-app settings.
 *
 * Privacy note: this flag is local-only. Nothing about it is sent to Telegram.
 * Even when set to true, the app never registers the device with Telegram in
 * any form — no MTProto session, no `RegisterDevice` call.
 */
class GuestModeStore(
    private val dataStore: DataStore<Preferences>,
) {

    val isGuest: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_GUEST_MODE] ?: false
    }

    suspend fun current(): Boolean = isGuest.first()

    suspend fun setGuest(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_GUEST_MODE] = enabled }
    }

    companion object {
        const val FILE_NAME = "guest_mode"
        private val KEY_GUEST_MODE = booleanPreferencesKey("guest_mode")
    }
}
