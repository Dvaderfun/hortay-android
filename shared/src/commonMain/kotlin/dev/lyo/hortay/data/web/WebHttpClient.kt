package dev.lyo.hortay.data.web

import io.ktor.client.HttpClient

/**
 * Build the default HTTP client for the web pipeline.
 *
 * Android: OkHttp engine with disk cache rooted at [cacheRootPath] / "web-http",
 * 10 s connect / 15 s read / 20 s call timeouts, 8-connection pool, no auto-
 * redirect (we detect t.me/s/foo → t.me/foo private-channel redirects).
 * iOS: Darwin engine with NSURLSession URLCache, same timeout shape, no
 * follow-redirect.
 *
 * [cacheRootPath] is the platform file path under which the HTTP cache stores
 * its entries. Pass null to skip the persistent cache (every fetch is a full
 * download). Android wires `context.cacheDir.absolutePath`; iOS wires
 * `NSCachesDirectory` via NSFileManager.
 */
expect fun defaultWebHttpClient(cacheRootPath: String? = null): HttpClient
