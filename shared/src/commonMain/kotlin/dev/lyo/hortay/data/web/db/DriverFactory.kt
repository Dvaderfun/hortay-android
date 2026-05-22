package dev.lyo.hortay.data.web.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific factory for the anonymous-mode SQLDelight driver. Pairs the
 * generated [WebDatabase.Schema] with the right native SQLite backend per target.
 *
 * Android: wraps [androidx.sqlite.db.SupportSQLiteOpenHelper] via
 * [app.cash.sqldelight.driver.android.AndroidSqliteDriver]. PRAGMA setup (WAL,
 * NORMAL sync, FK on) runs in the open callback — see actual impl.
 *
 * iOS: wraps system `sqlite3` via [app.cash.sqldelight.driver.native.NativeSqliteDriver].
 * iOS SQLite ships with WAL enabled by default and tolerates FK/temp-store
 * pragmas the same way Android does.
 *
 * Lifecycle: a single [DriverFactory] instance produces one driver. Hold the
 * driver for the entire app lifetime — reopening would lose the in-memory
 * statement cache and re-run PRAGMA setup per open.
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

/** Filename of the anonymous-mode database. Shared so iOS + Android resolve to the same name. */
internal const val WEB_DATABASE_FILE_NAME = "web.db"
