package dev.lyo.hortay.data.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Cache
import okhttp3.ConnectionPool
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val HTTP_CACHE_SIZE_BYTES = 10L * 1024 * 1024

actual fun defaultWebHttpClient(cacheRootPath: String?): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(15, TimeUnit.SECONDS)
            callTimeout(20, TimeUnit.SECONDS)
            connectionPool(ConnectionPool(8, 60, TimeUnit.SECONDS))
            followRedirects(false)
            followSslRedirects(false)
            if (cacheRootPath != null) {
                val cacheDir = File(cacheRootPath, "web-http")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                cache(Cache(cacheDir, HTTP_CACHE_SIZE_BYTES))
            }
        }
    }
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000L
    }
    install(HttpRequestRetry) {
        retryOnExceptionIf(maxRetries = 2) { _, cause -> cause is IOException }
        exponentialDelay(base = 2.0, maxDelayMs = 5_000L)
    }
}
