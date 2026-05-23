package dev.lyo.hortay.data.web.db

actual object WebDatabaseProvider {
    actual val FILE_NAME: String = WEB_DATABASE_FILE_NAME

    actual fun create(): WebDatabase = WebDatabase(DriverFactory().createDriver())
}
