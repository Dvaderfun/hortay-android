package dev.lyo.hortay.data.web.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = WebDatabase.Schema,
        name = WEB_DATABASE_FILE_NAME,
        // iOS sqlite3 ships with WAL by default; no need to issue PRAGMA
        // journal_mode = WAL. FK enforcement on iOS sqlite3 is also off by
        // default — set via onOpen if needed when stores actually move to
        // commonMain. For Phase A7 the driver itself is enough.
    )
}
