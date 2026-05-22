package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Process-wide Ktor [HttpClient] used by media composables that need to fetch raw
 * remote bytes (TGS Lottie payloads, sometimes WebM stickers without TDLib file
 * ids). Provided once from [dev.lyo.hortay.MainActivity], default value is a
 * minimal client so previews / previews-only consumers don't crash.
 *
 * Migrated from OkHttpClient → Ktor HttpClient in Phase A3. Uses the OkHttp
 * engine on Android (keeps disk cache + connection pool config) and Darwin on
 * iOS. AppGraph constructs the real client with the shared disk cache.
 */
val LocalWebHttpClient = staticCompositionLocalOf<HttpClient> {
    HttpClient(OkHttp)
}
