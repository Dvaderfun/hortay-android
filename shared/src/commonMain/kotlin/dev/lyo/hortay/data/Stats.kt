package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable

@Immutable
data class NetworkUsage(val rxBytes: Long, val txBytes: Long, val sinceMs: Long)

@Immutable
data class StorageUsage(val totalFilesBytes: Long, val databaseSizeBytes: Long)

/**
 * Platform-agnostic surface for TDLib's network + storage stats — used by
 * SettingsScreen. Android implements via `StatsRepository`. iOS has no stats
 * pipeline; the backend hands back null and SettingsScreen hides the cards.
 */
interface StatsFacade {
    suspend fun networkUsage(): NetworkUsage
    suspend fun storageUsage(): StorageUsage
    suspend fun clearCache(): Result<Unit>
    suspend fun resetTrafficStats(): Result<Unit>
}
