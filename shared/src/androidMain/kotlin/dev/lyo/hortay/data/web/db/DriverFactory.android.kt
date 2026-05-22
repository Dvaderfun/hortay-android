package dev.lyo.hortay.data.web.db

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

private const val TAG = "WebDatabase"

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver = AndroidSqliteDriver(
        schema = WebDatabase.Schema,
        context = context.applicationContext,
        name = WEB_DATABASE_FILE_NAME,
        callback = object : AndroidSqliteDriver.Callback(WebDatabase.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // PRAGMA journal_mode = WAL returns a single-row result — must
                // go through query() not execSQL(). Setter-form pragmas (no
                // return) use execSQL safely. See WebDatabaseProvider KDoc for
                // the per-PRAGMA rationale (WAL, NORMAL sync, FK on, temp_store).
                db.query("PRAGMA journal_mode = WAL").use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous = NORMAL")
                db.execSQL("PRAGMA foreign_keys = ON")
                db.execSQL("PRAGMA temp_store = MEMORY")
                Log.i(TAG, "web.db opened (WAL, FK on)")
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                Log.e(TAG, "web.db corruption detected; rebuilding")
                super.onCorruption(db)
            }
        },
    )
}
