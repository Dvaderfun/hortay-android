package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.web.defaultWebHttpClient
import io.ktor.client.HttpClient

/**
 * Process-wide Ktor [HttpClient] used by media composables that need to fetch raw
 * remote bytes (TGS Lottie payloads, sometimes WebM stickers without TDLib file
 * ids). Provided once from MainActivity / IosAppGraph, default value is a minimal
 * client so previews / previews-only consumers don't crash.
 *
 * Default uses the KMP [defaultWebHttpClient] factory so commonMain composables
 * fall back to the platform-appropriate engine (OkHttp on Android, Darwin on iOS)
 * without instantiating an Android-specific HttpClient.
 */
val LocalWebHttpClient = staticCompositionLocalOf<HttpClient> { defaultWebHttpClient() }
