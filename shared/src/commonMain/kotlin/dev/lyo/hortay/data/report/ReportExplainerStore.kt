// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.data.report

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists whether the one-time reporting explainer has been shown to the
 * user. Default false — shows once, then stays permanently dismissed.
 *
 * KMP: constructor takes the platform-provided [DataStore] handle. Mirrors
 * the [dev.lyo.hortay.data.web.GuestModeStore] pattern.
 */
class ReportExplainerStore(private val dataStore: DataStore<Preferences>) {

    /** Emits true once the user has seen and dismissed the explainer. */
    val shown: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOWN] ?: false
    }

    suspend fun markShown() {
        dataStore.edit { prefs -> prefs[KEY_SHOWN] = true }
    }

    companion object {
        const val FILE_NAME: String = "report_explainer"
        private val KEY_SHOWN = booleanPreferencesKey("report_explainer_shown")
    }
}
