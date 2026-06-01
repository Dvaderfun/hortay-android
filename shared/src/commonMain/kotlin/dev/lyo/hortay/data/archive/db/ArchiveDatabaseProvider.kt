package dev.lyo.hortay.data.archive.db

/**
 * Process-wide construction site for the post-archive database. The actual per-platform impl
 * builds an [ArchiveDriverFactory] (Android needs Context via
 * [dev.lyo.hortay.data.PlatformContextHolder]; iOS needs nothing) and wraps the driver with
 * [ArchiveDatabase].
 */
expect object ArchiveDatabaseProvider {
    val FILE_NAME: String
    fun create(): ArchiveDatabase
}
