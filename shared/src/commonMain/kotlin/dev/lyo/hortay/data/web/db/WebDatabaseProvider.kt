package dev.lyo.hortay.data.web.db

/**
 * Process-wide construction site for the anonymous-mode database. The actual
 * implementation per platform creates a [DriverFactory] (different constructor
 * shapes — Android needs Context via [dev.lyo.hortay.data.PlatformContextHolder];
 * iOS needs nothing) and wraps the driver with [WebDatabase].
 *
 * PRAGMA setup, WAL config, corruption handling — see [DriverFactory] actuals.
 */
expect object WebDatabaseProvider {
    val FILE_NAME: String
    fun create(): WebDatabase
}
