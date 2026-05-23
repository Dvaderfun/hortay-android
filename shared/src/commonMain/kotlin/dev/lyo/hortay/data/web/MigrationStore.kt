package dev.lyo.hortay.data.web

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the state of the guest→TDLib subscription migration proposal so it
 * doesn't re-show every time the user reaches AuthStage.Ready.
 *
 * Two pieces of state:
 *   - proposalShown — bumped once when the proposal sheet has been displayed
 *     to the user. The sheet renders then dismisses; a "skip" still counts as
 *     shown. We don't reopen on every auth round-trip.
 *   - migratedUsernames — the bare-username set the user actually approved
 *     for migration. Used to skip them on a future re-migration.
 */
class MigrationStore(
    private val ds: DataStore<Preferences>,
) {

    val proposalShown: Flow<Boolean> = ds.data.map { it[KEY_PROPOSAL_SHOWN] ?: false }

    suspend fun isProposalShown(): Boolean = proposalShown.first()

    suspend fun markProposalShown() {
        ds.edit { it[KEY_PROPOSAL_SHOWN] = true }
    }

    val migratedUsernames: Flow<Set<String>> = ds.data.map { it[KEY_MIGRATED] ?: emptySet() }

    suspend fun addMigrated(usernames: Iterable<String>) {
        val incoming = usernames.toSet()
        if (incoming.isEmpty()) return
        ds.edit { prefs ->
            val current = prefs[KEY_MIGRATED] ?: emptySet()
            prefs[KEY_MIGRATED] = current + incoming
        }
    }

    suspend fun reset() {
        ds.edit { prefs ->
            prefs.remove(KEY_PROPOSAL_SHOWN)
            prefs.remove(KEY_MIGRATED)
        }
    }

    companion object {
        const val FILE_NAME = "guest_migration"
        private val KEY_PROPOSAL_SHOWN = booleanPreferencesKey("guest_migration_proposal_shown")
        private val KEY_MIGRATED = stringSetPreferencesKey("guest_migration_migrated_usernames")
    }
}
