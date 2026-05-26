package dev.lyo.hortay.data

/**
 * Persists a tiny "what was on the user's screen last time" snapshot so cold start
 * can show real content in <100ms instead of a blank feed for the multi-second
 * `refresh()` round-trip storm to land.
 *
 * Storage shape: just a flat list of `(chatId, messageId)` pairs — TDLib already
 * keeps the full message bodies on disk, and asking `GetMessage` for ids it
 * has cached is essentially free (sub-millisecond, no network).
 *
 * Platform implementations:
 *   - Android: [DataStoreTimelineSnapshotStore] (DataStore preferences)
 *   - iOS: no-op until Phase II-D1 wires a file-based store
 */
interface TimelineSnapshotStore {
    suspend fun load(): List<Pair<Long, Long>>
    suspend fun save(entries: List<Pair<Long, Long>>)
    suspend fun clear()
}
