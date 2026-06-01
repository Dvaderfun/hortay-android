package dev.lyo.hortay.di

import dev.lyo.hortay.data.AndroidTdSenderAdapter
import dev.lyo.hortay.data.AutoDownloadStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.CountryRepository
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.DataStoreTimelineSnapshotStore
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.LogoutCleanup
import dev.lyo.hortay.data.MediaAutoDownloader
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.NavStack
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.ProfileAccentRegistry
import dev.lyo.hortay.data.ProfileAccentResolver
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.archive.AndroidArchivedMediaStore
import dev.lyo.hortay.data.archive.ArchiveLogoutClear
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveSettingsStore
import dev.lyo.hortay.data.archive.ArchivedMediaStore
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.proxy.ProxyRepository
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdLifecycleBridge
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.TelegramLinkResolver
import dev.lyo.hortay.data.TimelineSnapshotStore
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.report.ReportRepository
import dev.lyo.hortay.data.web.MigrationCoordinator
import dev.lyo.hortay.data.web.MigrationStore
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebCustomEmojiBridge
import dev.lyo.hortay.data.web.WebCustomEmojiResolver
import dev.lyo.hortay.data.web.WebFeedScheduler
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.ui.media.StickerOutlineStore
import dev.lyo.hortay.ui.media.VideoPlayerPool
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * TDLib-bound graph for authenticated mode. Mirrors the historical `AppGraph`
 * wiring 1:1.
 *
 * **Eager singletons (`createdAtStart`).** TDLib + its lifecycle bridge +
 * the auto-downloader + the web scheduler + the migration coordinator + the
 * web-custom-emoji bridge all need their `start()` / `bind()` side-effect
 * to run BEFORE any consumer touches them. Koin's `createdAtStart`
 * guarantees instantiation at `startKoin {}` time in module-declaration
 * order — matching the historical Kotlin-property-order contract in
 * `AppGraph`.
 *
 * **`LogoutCleanup`.** Eager — its `bind()` subscribes to
 * `tdClient.loggedOut` to orchestrate per-account cache wipes. Subscription
 * must be active before the user can log out, hence eager.
 *
 * **`TdClient` bound as `TdSender`.** Every repo takes `TdSender` (the
 * Phase-II seam). `bind<TdSender>()` makes `get<TdSender>()` resolve to the
 * single `TdClient` instance.
 *
 * **`TimelineSnapshotStore` interface binding.** `DataStoreTimelineSnapshotStore`
 * implements the commonMain `TimelineSnapshotStore` interface;
 * `PostsRepository` consumes the interface, so we bind both names.
 *
 * **`MediaCache` / `CustomEmojiRepository` / `VideoPlayerPool`** are
 * `expect class` types — Android's `actual class` ctors take TDLib deps,
 * iOS uses no-arg stubs (see `TdlibStubModule`).
 */
val tdlibModule = module {
    // ---- TDLib core --------------------------------------------------------
    single(createdAtStart = true) {
        TdClient.create(androidContext(), get<SettingsStore>()).also { it.start() }
    }
    // Adapter bridges TdClient (Java TdApi, JNI) → TdSender (Kotlin TdApi,
    // commonMain). Repositories injected with TdSender live in commonMain and
    // are shared across Android + iOS.
    single<TdSender> { AndroidTdSenderAdapter(get<TdClient>(), get<CoroutineScope>()) }

    single(createdAtStart = true) {
        TdLifecycleBridge(
            td = get<TdClient>(),
            context = androidContext(),
            scope = get<CoroutineScope>(),
            settings = get<SettingsStore>(),
        ).also { it.bind() }
    }

    // ---- Snapshot + media + media-cache fan-out ----------------------------
    single<TimelineSnapshotStore> { DataStoreTimelineSnapshotStore(androidContext()) }

    single {
        MediaCache(
            td = get<TdSender>(),
            scope = get<CoroutineScope>(),
            connection = get<TdClient>().connection,
            foreground = get<TdLifecycleBridge>().foreground,
            networkType = get<TdLifecycleBridge>().networkType,
            res = get<StringResolver>(),
        )
    }

    // ---- Message-graph repos ----------------------------------------------
    single { MessageMapper(get<TdSender>(), get<StringResolver>()) }

    single {
        PostsRepository(
            td = get<TdSender>(),
            mapper = get(),
            scope = get<CoroutineScope>(),
            userMessages = get<UserMessageBus>(),
            connection = get<TdClient>().connection,
            snapshotStore = get<TimelineSnapshotStore>(),
            foreground = get<TdLifecycleBridge>().foreground,
            res = get<StringResolver>(),
            ignoredChannels = getOrNull(),
        )
    }

    single { CommentsRepository(get<TdSender>(), get(), get<CoroutineScope>(), get<StringResolver>()) }

    single { ChannelActionsRepository(get<TdSender>(), get<UserMessageBus>(), get<TdClient>().connection, get<StringResolver>()) }

    single { ChatFoldersRepository(get<TdSender>(), get<CoroutineScope>()) }

    single { CountryRepository(get<TdSender>(), get<StringResolver>(), get<CoroutineScope>()) }

    single { TelegramLinkResolver(get<TdSender>()).also { it.bindLogoutClear(get<TdClient>().loggedOut, get<CoroutineScope>()) } }

    single { StatsRepository(get<TdSender>()) }

    single { TranslationsStore(get<TdSender>(), get<CoroutineScope>(), get<UserMessageBus>(), get<TdClient>().connection, get<StringResolver>()) }

    // ---- Custom emoji + sticker outline ------------------------------------
    single { CustomEmojiRepository(get<TdSender>(), get<CoroutineScope>()) }

    single { StickerOutlineStore(get<TdSender>()) }

    single { VideoPlayerPool() }

    // ---- Startup gate + auto-download --------------------------------------
    single {
        StartupCoordinator(
            authStage = get<TdClient>().authStage,
            posts = get<PostsRepository>().posts,
            scope = get<CoroutineScope>(),
        )
    }

    single {
        AutoDownloadStore(
            context = androidContext(),
            td = get<TdSender>(),
            authStage = get<TdClient>().authStage,
            scope = get<CoroutineScope>(),
        )
    }

    single(createdAtStart = true) {
        MediaAutoDownloader(
            store = get(),
            cache = get<MediaCache>(),
            newArrivalsFlow = get<PostsRepository>().newArrivals,
            networkType = get<TdLifecycleBridge>().networkType,
            connection = get<TdClient>().connection,
            foreground = get<TdLifecycleBridge>().foreground,
            startupPhase = get<StartupCoordinator>().phase,
            scope = get<CoroutineScope>(),
        ).also { it.bind() }
    }

    // ---- Web pipeline cross-mode bridges (TDLib-mode only) -----------------
    single(createdAtStart = true) {
        WebFeedScheduler(
            feedSource = get<WebFeedSource>(),
            foreground = get<TdLifecycleBridge>().foreground,
            authStage = get<TdClient>().authStage,
            isGuest = get<dev.lyo.hortay.data.web.GuestModeStore>().isGuest,
            scope = get<CoroutineScope>(),
        ).also { it.bind() }
    }

    single(createdAtStart = true) {
        WebCustomEmojiBridge(
            resolver = get<WebCustomEmojiResolver>(),
            customEmojiRepo = get<CustomEmojiRepository>(),
            feed = get<WebFeedSource>().posts,
            scope = get<CoroutineScope>(),
        ).also { it.bind() }
    }

    // ---- Migration ---------------------------------------------------------
    single(createdAtStart = true) {
        MigrationCoordinator(
            migrationStore = get<MigrationStore>(),
            subscriptions = get<SubscriptionsStore>(),
            postsRepository = get(),
            channelActions = get(),
            authStage = get<TdClient>().authStage,
            scope = get<CoroutineScope>(),
        ).also { it.bind() }
    }

    // ---- Post archive (TDLib-side platform deps; DB/repo/sweep/VMs live in archiveModule) --
    single<ArchivedMediaStore> { AndroidArchivedMediaStore(androidContext(), get<ArchiveDatabase>()) }
    single(createdAtStart = true) {
        ArchiveLogoutClear(
            repo = get<ArchiveRepository>(),
            settingsStore = get<ArchiveSettingsStore>(),
            loggedOut = get<TdClient>().loggedOut,
            scope = get<CoroutineScope>(),
        )
    }

    // ---- Proxy (eager — its init launches the pool refresh + failover watchdog) -----------
    single(createdAtStart = true) {
        ProxyRepository(
            sender = get<TdSender>(),
            connection = get<TdClient>().connection,
            userMessages = get<UserMessageBus>(),
            scope = get<CoroutineScope>(),
            res = get<StringResolver>(),
        )
    }

    // ---- Profile accent palette (eager — collects the one-shot UpdateProfileAccentColors) --
    single<ProfileAccentResolver>(createdAtStart = true) {
        ProfileAccentRegistry().also {
            it.bind(get<TdClient>().updates, get<TdClient>().loggedOut, get<CoroutineScope>())
        }
    }

    // ---- Reporting ---------------------------------------------------------
    single { ReportRepository(get<TdSender>(), get<StringResolver>(), get<ReportLogStore>()) }

    // ---- HortayBackend (Android variant — wires real TDLib repos) ----------
    single {
        HortayBackend(
            client = get<TdClient>(),
            countriesRepo = get(),
            channelActions = get(),
            linkResolver = get(),
            postsRepo = get(),
            commentsRepo = get(),
            folders = get<ChatFoldersRepository>(),
            reportRepo = get(),
            reportDialogs = get<ReportDialogState>(),
            reportLogStore = get<ReportLogStore>(),
            reportExplainerStore = get<ReportExplainerStore>(),
            settingsStore = get<SettingsStore>(),
            stats = get<StatsRepository>(),
            autoDownload = get<AutoDownloadStore>(),
            translationsStore = get<TranslationsStore>(),
            backendScope = get<CoroutineScope>(),
        )
    }

    // ---- Logout cleanup (eager — subscribes to tdClient.loggedOut) ---------
    single(createdAtStart = true) {
        LogoutCleanup(
            tdClient = get<TdClient>(),
            appScope = get<CoroutineScope>(),
            postsRepository = get(),
            messageMapper = get(),
            commentsRepository = get(),
            mediaCache = get<MediaCache>(),
            customEmoji = get<CustomEmojiRepository>(),
            stickerOutline = get(),
            chatFoldersRepository = get(),
            translations = get(),
            migrationStore = get<MigrationStore>(),
            reportDialogs = get<ReportDialogState>(),
            nav = get<NavStack>(),
        ).also { it.bind() }
    }
}
