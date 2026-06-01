package dev.lyo.hortay.data.archive.db

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

private const val TAG = "ArchiveDatabase"

actual class ArchiveDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver = AndroidSqliteDriver(
        schema = ArchiveDatabase.Schema,
        context = context.applicationContext,
        name = ARCHIVE_DATABASE_FILE_NAME,
        callback = object : AndroidSqliteDriver.Callback(ArchiveDatabase.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // journal_mode = WAL returns a row — must go through query(); setter pragmas
                // use execSQL. Same portability rationale as the web.db driver.
                db.query("PRAGMA journal_mode = WAL").use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous = NORMAL")
                db.execSQL("PRAGMA foreign_keys = ON")
                db.execSQL("PRAGMA temp_store = MEMORY")
                Log.i(TAG, "archive.db opened (WAL, FK on)")
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                Log.e(TAG, "archive.db corruption detected; rebuilding")
                super.onCorruption(db)
            }
        },
    )
}
