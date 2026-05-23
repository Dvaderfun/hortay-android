package dev.lyo.hortay.data.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout

actual fun defaultWebHttpClient(cacheRootPath: String?): HttpClient = HttpClient(Darwin) {
    engine {
        configureRequest {
            // No-redirect handling lives at the request level on Darwin.
        }
    }
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000L
        connectTimeoutMillis = 10_000L
        socketTimeoutMillis = 15_000L
    }
    install(HttpRequestRetry) {
        retryOnExceptionIf(maxRetries = 2) { _, cause -> cause !is kotlin.coroutines.cancellation.CancellationException }
        exponentialDelay(base = 2.0, maxDelayMs = 5_000L)
    }
}
