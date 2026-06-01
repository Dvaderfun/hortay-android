package dev.lyo.hortay.data.archive.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific factory for the post-archive SQLDelight driver. Pairs the generated
 * [ArchiveDatabase.Schema] with the right native SQLite backend per target — same shape as the
 * web-mode [dev.lyo.hortay.data.web.db.DriverFactory], but a separate database file
 * ([ARCHIVE_DATABASE_FILE_NAME]) with its own lifecycle (survives logout).
 */
expect class ArchiveDriverFactory {
    fun createDriver(): SqlDriver
}

/** Filename of the post-archive database. Shared so iOS + Android resolve to the same name. */
internal const val ARCHIVE_DATABASE_FILE_NAME = "archive.db"
