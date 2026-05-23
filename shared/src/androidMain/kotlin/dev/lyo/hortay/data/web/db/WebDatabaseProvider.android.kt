package dev.lyo.hortay.data.web.db

import dev.lyo.hortay.data.PlatformContextHolder

actual object WebDatabaseProvider {
    actual val FILE_NAME: String = WEB_DATABASE_FILE_NAME

    actual fun create(): WebDatabase {
        val context = PlatformContextHolder.require()
        return WebDatabase(DriverFactory(context).createDriver())
    }
}
