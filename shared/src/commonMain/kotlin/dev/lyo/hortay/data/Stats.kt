package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable

@Immutable
data class NetworkUsage(val rxBytes: Long, val txBytes: Long, val sinceMs: Long)

@Immutable
data class StorageUsage(val totalFilesBytes: Long, val databaseSizeBytes: Long)
