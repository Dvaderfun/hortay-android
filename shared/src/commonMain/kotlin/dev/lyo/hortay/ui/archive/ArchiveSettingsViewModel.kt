package dev.lyo.hortay.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.archive.ArchiveFilter
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.data.archive.ArchiveSettingsStore
import dev.lyo.hortay.data.archive.ArchiveSweep
import dev.lyo.hortay.data.archive.ChatRef
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okio.BufferedSink

@OptIn(FlowPreview::class)
class ArchiveSettingsViewModel(
    private val store: ArchiveSettingsStore,
    private val repo: ArchiveRepository,
    private val sweep: ArchiveSweep,
) : ViewModel() {

    val settings: StateFlow<ArchiveSettings> =
        store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, ArchiveSettings.DEFAULT)

    /** Live snapshot count for the "Open archive — N posts" subtitle, debounced. */
    val snapshotCount: StateFlow<Int> =
        repo.observe(ArchiveFilter()).debounce(SETTINGS_REFRESH_DEBOUNCE_MS).map { it.size }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Live storage estimate (bytes), debounced more aggressively (full-table SUM scan). */
    val storageBytes: StateFlow<Long> =
        repo.observe(ArchiveFilter())
            .debounce(STORAGE_BYTES_DEBOUNCE_MS)
            .map { repo.storageBytes() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun confirmEnableFromOnboarding() {
        viewModelScope.launch {
            store.setOnboardingSeen(true)
            store.setEnabled(true)
        }
    }

    fun disable(deleteArchive: Boolean) {
        viewModelScope.launch {
            store.setEnabled(false)
            if (deleteArchive) repo.clear()
        }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            store.setRetentionDays(days)
            sweep.run()
        }
    }

    fun setMaxRecords(n: Int) {
        viewModelScope.launch {
            store.setMaxRecords(n)
            sweep.run()
        }
    }

    fun setCaptureEdits(v: Boolean) {
        viewModelScope.launch { store.setCaptureEdits(v) }
    }

    fun setCaptureDeletes(v: Boolean) {
        viewModelScope.launch { store.setCaptureDeletes(v) }
    }

    fun setExcludedChats(refs: Collection<ChatRef>) {
        viewModelScope.launch { store.setExcludedChats(refs) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clear() }
    }

    /**
     * Streams the archive into [sink] and returns the number of records written. The caller owns
     * opening/closing the sink (some callers — SAF Uri — close it themselves).
     */
    suspend fun exportTo(sink: BufferedSink): Int = repo.exportTo(sink)

    suspend fun peekStorageBytes(): Long = repo.storageBytes()

    private companion object {
        const val SETTINGS_REFRESH_DEBOUNCE_MS = 500L
        const val STORAGE_BYTES_DEBOUNCE_MS = 2000L
    }
}
