package dev.lyo.hortay.data.archive

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists [ArchiveSettings]. Backed by the KMP Preferences DataStore (`datastore-preferences-core`
 * + the Okio-backed factory) — takes the [DataStore] from DI rather than the Android-only
 * `preferencesDataStore(name)` Context delegate, so the store compiles on every target.
 */
class ArchiveSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {

    val flow: Flow<ArchiveSettings> = dataStore.data.map { prefs ->
        ArchiveSettings(
            enabled = prefs[K_ENABLED] ?: false,
            onboardingSeen = prefs[K_ONBOARDING_SEEN] ?: false,
            retentionDays = prefs[K_RETENTION_DAYS] ?: 30,
            maxRecords = prefs[K_MAX_RECORDS] ?: 5000,
            excludedChats = (prefs[K_EXCLUDED] ?: emptySet())
                .mapNotNull { decode(it) }.toPersistentSet(),
            captureEdits = prefs[K_CAPTURE_EDITS] ?: true,
            captureDeletes = prefs[K_CAPTURE_DELETES] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) = update { it[K_ENABLED] = v }
    suspend fun setOnboardingSeen(v: Boolean) = update { it[K_ONBOARDING_SEEN] = v }
    suspend fun setRetentionDays(v: Int) = update { it[K_RETENTION_DAYS] = v }
    suspend fun setMaxRecords(v: Int) = update { it[K_MAX_RECORDS] = v }
    suspend fun setCaptureEdits(v: Boolean) = update { it[K_CAPTURE_EDITS] = v }
    suspend fun setCaptureDeletes(v: Boolean) = update { it[K_CAPTURE_DELETES] = v }
    suspend fun setExcludedChats(refs: Collection<ChatRef>) = update {
        it[K_EXCLUDED] = refs.map(::encode).toSet()
    }

    /**
     * Drop per-account state on logout. The master `enabled` toggle is a user preference and
     * survives — but the excluded-chat set is keyed on the previous account's TDLib chatIds, which
     * become meaningless once a different account signs in.
     */
    suspend fun resetForLogout() = update { it.remove(K_EXCLUDED) }

    private suspend fun update(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun encode(ref: ChatRef): String = "${ref.kind.name}|${ref.key}"
    private fun decode(raw: String): ChatRef? {
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2) return null
        val kind = runCatching { SourceKind.valueOf(parts[0]) }.getOrNull() ?: return null
        return ChatRef(kind, parts[1])
    }

    companion object {
        const val FILE_NAME = "archive_settings"

        private val K_ENABLED = booleanPreferencesKey("enabled")
        private val K_ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        private val K_RETENTION_DAYS = intPreferencesKey("retention_days")
        private val K_MAX_RECORDS = intPreferencesKey("max_records")
        private val K_EXCLUDED = stringSetPreferencesKey("excluded_chats")
        private val K_CAPTURE_EDITS = booleanPreferencesKey("capture_edits")
        private val K_CAPTURE_DELETES = booleanPreferencesKey("capture_deletes")
    }
}
