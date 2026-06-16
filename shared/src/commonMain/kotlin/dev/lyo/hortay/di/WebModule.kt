package dev.lyo.hortay.di

import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebCustomEmojiResolver
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.db.WebDatabase
import dev.lyo.hortay.data.web.db.WebDatabaseProvider
import dev.lyo.hortay.data.web.defaultWebHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.module

/**
 * Per-platform Ktor HttpClient cache root. Android maps to
 * `cacheDir/web-http/` for the ETag-aware OkHttp cache; iOS passes `null`
 * (Darwin engine uses URLCache's default location). Resolved by
 * `platformModule()` so this module stays in commonMain.
 */
data class WebHttpCacheConfig(val cacheRootPath: String?)

/**
 * Web-mode (guest) pipeline. Every consumer lives in commonMain — the same
 * pipeline drives Android guest mode AND iOS guest mode.
 *
 * `WebDatabase` is a process-singleton: re-opening the SQLDelight driver
 * per call would cost ~50-100 ms of WAL-mode setup. `defaultWebHttpClient`
 * is shared across `WebTelegramClient`, `WebCustomEmojiResolver`, and the
 * Compose UI's `LocalWebHttpClient` slot — connection-pool reuse + ETag
 * cache cuts a 200-channel sweep ~80-90% on a warm cache.
 */
val webModule = module {
    single<WebDatabase> { WebDatabaseProvider.create() }
    single<HttpClient> { defaultWebHttpClient(cacheRootPath = get<WebHttpCacheConfig>().cacheRootPath) }
    // Curated channel-suggestions catalog (remote, locale-aware). Shared by guest
    // AND authenticated add-channel sheets — only hydration differs per mode.
    single {
        dev.lyo.hortay.data.discover.ChannelSuggestionsRepository(
            http = get<HttpClient>(),
            cacheFile = dev.lyo.hortay.applicationFilesPath("suggestions_catalog.json"),
            appVersionCode = dev.lyo.hortay.BuildKonfig.VERSION_CODE,
        )
    }
    single { WebTelegramClient(get()) }
    single { WebCustomEmojiResolver(get()) }
    single { WebRepository(db = get(), strings = get<StringResolver>()) }
    single {
        WebFeedSource(
            client = get(),
            repository = get(),
            subscriptions = get<SubscriptionsStore>(),
            scope = get<CoroutineScope>(),
            ignoredChannels = get<IgnoredChannelsStore>(),
            archiveRepository = getOrNull(),
        )
    }
}
