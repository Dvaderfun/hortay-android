package dev.lyo.hortay.data.archive.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class ArchiveDriverFactory {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = ArchiveDatabase.Schema,
        name = ARCHIVE_DATABASE_FILE_NAME,
    )
}
