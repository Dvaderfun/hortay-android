package dev.lyo.hortay.data.archive.db

import dev.lyo.hortay.data.PlatformContextHolder

actual object ArchiveDatabaseProvider {
    actual val FILE_NAME: String = ARCHIVE_DATABASE_FILE_NAME

    actual fun create(): ArchiveDatabase {
        val context = PlatformContextHolder.require()
        return ArchiveDatabase(ArchiveDriverFactory(context).createDriver())
    }
}
