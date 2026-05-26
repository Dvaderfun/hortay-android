package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Production [TimelineSnapshotStore] backed by Android DataStore. Encoding: pipe-
 * separated `chatId,messageId` pairs.
 */
class DataStoreTimelineSnapshotStore(context: Context) : TimelineSnapshotStore {

    private val dataStore = context.applicationContext.snapshotDataStore

    override suspend fun load(): List<Pair<Long, Long>> {
        val packed = dataStore.data.first()[KEY].orEmpty()
        if (packed.isEmpty()) return emptyList()
        return packed.split(SEPARATOR_ENTRY).mapNotNull(::parseEntry)
    }

    override suspend fun save(entries: List<Pair<Long, Long>>) {
        val packed = entries.joinToString(SEPARATOR_ENTRY.toString()) { (c, m) -> "$c$SEPARATOR_FIELD$m" }
        dataStore.edit { it[KEY] = packed }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private fun parseEntry(text: String): Pair<Long, Long>? {
        val parts = text.split(SEPARATOR_FIELD, limit = 2)
        if (parts.size != 2) return null
        val chatId = parts[0].toLongOrNull() ?: return null
        val messageId = parts[1].toLongOrNull() ?: return null
        return chatId to messageId
    }

    private companion object {
        val KEY = stringPreferencesKey("timeline_snapshot_v1")
        const val SEPARATOR_ENTRY = '|'
        const val SEPARATOR_FIELD = ','
    }
}

private val Context.snapshotDataStore by preferencesDataStore(name = "timeline_snapshot")
