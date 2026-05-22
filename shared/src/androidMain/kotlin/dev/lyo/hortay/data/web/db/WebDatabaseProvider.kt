package dev.lyo.hortay.data.web.db

import android.content.Context

/**
 * Process-wide construction site for the anonymous-mode database. Delegates to
 * [DriverFactory] (Phase A7 KMP refactor) which owns the platform-specific
 * driver lifecycle. Kept as a stable Android entry point so AppGraph doesn't
 * need to know the multiplatform plumbing.
 *
 * PRAGMA setup, WAL config, corruption handling — all in
 * [DriverFactory.android.kt]. See [WebDatabase] (generated) for the schema.
 *
 * Why a separate provider object instead of `DriverFactory(context).createDriver()`
 * inlined into [dev.lyo.hortay.AppGraph]: schema migration plumbing (verifyMigrations +
 * future `<n>.sqm` files) lives alongside this provider, and the FILE_NAME
 * constant is referenced from log filters / test fixtures.
 */
object WebDatabaseProvider {

    const val FILE_NAME = WEB_DATABASE_FILE_NAME

    fun create(context: Context): WebDatabase =
        WebDatabase(DriverFactory(context).createDriver())
}
