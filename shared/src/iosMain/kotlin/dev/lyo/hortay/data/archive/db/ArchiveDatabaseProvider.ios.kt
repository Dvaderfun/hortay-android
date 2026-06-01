package dev.lyo.hortay.data.archive.db

actual object ArchiveDatabaseProvider {
    actual val FILE_NAME: String = ARCHIVE_DATABASE_FILE_NAME

    actual fun create(): ArchiveDatabase = ArchiveDatabase(ArchiveDriverFactory().createDriver())
}
