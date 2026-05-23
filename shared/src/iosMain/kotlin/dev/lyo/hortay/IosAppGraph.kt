package dev.lyo.hortay

import dev.lyo.hortay.data.ComposeResourcesStringResolver
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.createPreferencesDataStore
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.db.WebDatabase
import dev.lyo.hortay.data.web.db.WebDatabaseProvider
import dev.lyo.hortay.data.web.defaultWebHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Slim DI root for iOS guest-mode. Owns the web pipeline only — no TDLib, no
 * ExoPlayer, no Coil video/gif. Mounted from [MainViewController].
 *
 * Created once at app start; held by the SwiftUI `App` instance for the
 * lifetime of the process. Coroutines launched here use [appScope], cancelled
 * only on process death.
 */
class IosAppGraph {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val strings: StringResolver = ComposeResourcesStringResolver()

    val subscriptions: SubscriptionsStore =
        SubscriptionsStore(createPreferencesDataStore(SubscriptionsStore.FILE_NAME))

    val guestMode: GuestModeStore =
        GuestModeStore(createPreferencesDataStore(GuestModeStore.FILE_NAME))

    val ignoredChannels: IgnoredChannelsStore =
        IgnoredChannelsStore(createPreferencesDataStore(IgnoredChannelsStore.FILE_NAME))

    val webDatabase: WebDatabase = WebDatabaseProvider.create()

    val webHttpClient: HttpClient = defaultWebHttpClient(cacheRootPath = null)

    val webClient: WebTelegramClient = WebTelegramClient(webHttpClient)

    val webRepository: WebRepository = WebRepository(db = webDatabase, strings = strings)

    val webFeedSource: WebFeedSource = WebFeedSource(
        client = webClient,
        repository = webRepository,
        subscriptions = subscriptions,
        scope = appScope,
        ignoredChannels = ignoredChannels,
    )

    val settingsStore: SettingsStore =
        SettingsStore(createPreferencesDataStore(SettingsStore.FILE_NAME))

    val reportLogStore: ReportLogStore = ReportLogStore()

    val reportExplainerStore: ReportExplainerStore =
        ReportExplainerStore(createPreferencesDataStore(ReportExplainerStore.FILE_NAME))

    /** Stub backend so commonMain UI screens compile on iOS. Guest-mode UI never calls into it. */
    val backend: HortayBackend = HortayBackend(
        settingsStore = settingsStore,
        reportLogStore = reportLogStore,
        reportExplainerStore = reportExplainerStore,
    )
}
