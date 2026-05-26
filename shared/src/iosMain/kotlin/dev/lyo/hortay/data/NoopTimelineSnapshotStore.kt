package dev.lyo.hortay.data

internal object NoopTimelineSnapshotStore : TimelineSnapshotStore {
    override suspend fun load(): List<Pair<Long, Long>> = emptyList()
    override suspend fun save(entries: List<Pair<Long, Long>>) {}
    override suspend fun clear() {}
}
