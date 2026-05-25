package dev.lyo.hortay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.data.LocaleStore
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.NavStack
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.data.TelegramLinkResolver
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.data.web.MigrationCoordinator
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.ui.auth.AuthScreen
import dev.lyo.hortay.ui.main.MainScaffold
import dev.lyo.hortay.ui.media.LocalAvatarFileLoader
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalStickerOutline
import dev.lyo.hortay.ui.media.LocalVideoPlayerPool
import dev.lyo.hortay.ui.media.LocalWebHttpClient
import dev.lyo.hortay.ui.media.MediaViewerHost
import dev.lyo.hortay.ui.media.StickerOutlineStore
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.VideoPlayerPool
import dev.lyo.hortay.ui.report.GuestReportDelegator
import dev.lyo.hortay.ui.report.GuestReportOutcome
import dev.lyo.hortay.ui.report.LocalGuestReportDelegate
import dev.lyo.hortay.ui.theme.HortayTheme
import dev.lyo.hortay.ui.theme.LocalStatusBarController
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.lyo.hortay.ui.web.MigrationProposalSheet
import dev.lyo.hortay.ui.web.WebModeScaffold
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {

    // Non-Composable injections — used in onCreate / onNewIntent before
    // setContent runs. Koin's `org.koin.android.ext.android.inject` is the
    // lifecycle-aware delegate for Activity/Fragment.
    private val appScope: CoroutineScope by inject()
    private val linkResolver: TelegramLinkResolver by inject()
    private val deepLinkRouter: DeepLinkRouter by inject()
    private val guestReportDelegator: GuestReportDelegator by inject()

    // API 26-32 path for the in-app language picker. AppCompatDelegate.setApplicationLocales
    // is a no-op without an AppCompatActivity in the process (it dispatches through an
    // internal sActivityDelegates set), so LocaleStore wraps the base context with the
    // user-chosen locale before resources resolve. API 33+ is handled by the platform
    // LocaleManager and this wrap is a no-op there. See LocaleStore for the rationale.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cold-launch deep link: resolve + buffer into the router before MainScaffold's
        // collector subscribes. Resolution is async (TDLib GetInternalLinkType is an
        // offline JNI call but still a coroutine boundary) — appScope.launch wins the
        // race in practice because the Channel buffers the resulting event regardless
        // of subscriber arrival ordering. Warm launches arrive via [onNewIntent] below.
        intent?.data?.let { uri ->
            appScope.launch {
                linkResolver.resolve(uri)?.let { deepLinkRouter.submit(it) }
            }
        }

        setContent {
            // Resolve every shared singleton through Koin at the top of setContent.
            // Inner composables keep their explicit params (Compose best practice —
            // explicit data flow, easier to preview / test in isolation).
            val tdClient = koinInject<TdClient>()
            val backend = koinInject<HortayBackend>()
            val postsRepository = koinInject<PostsRepository>()
            val bookmarks = koinInject<BookmarkStore>()
            val ignoredChannels = koinInject<IgnoredChannelsStore>()
            val guestMode = koinInject<GuestModeStore>()
            val userMessages = koinInject<UserMessageBus>()
            val linkDialogs = koinInject<LinkDialogState>()
            val deepLinkRouterCompose = koinInject<DeepLinkRouter>()
            val startupCoordinator = koinInject<StartupCoordinator>()
            val nav = koinInject<NavStack>()
            val appScopeCompose = koinInject<CoroutineScope>()
            val mediaCache = koinInject<MediaCache>()
            val customEmoji = koinInject<CustomEmojiRepository>()
            val stickerOutline = koinInject<StickerOutlineStore>()
            val videoPlayerPool = koinInject<VideoPlayerPool>()
            val webHttpClient = koinInject<HttpClient>()
            val webSubscriptions = koinInject<SubscriptionsStore>()
            val webFeedSource = koinInject<WebFeedSource>()
            val webRepository = koinInject<WebRepository>()
            val webClient = koinInject<WebTelegramClient>()
            val settingsStore = koinInject<SettingsStore>()
            val migrationCoordinator = koinInject<MigrationCoordinator>()
            val guestReportDelegatorCompose = koinInject<GuestReportDelegator>()

            val view = LocalView.current
            val window = this.window
            val statusBar: (Boolean) -> Unit = { light ->
                if (!view.isInEditMode) {
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = light
                }
            }
            CompositionLocalProvider(LocalStatusBarController provides statusBar) {
            HortayTheme {
                CompositionLocalProvider(
                    LocalMediaCache provides mediaCache,
                    LocalCustomEmoji provides customEmoji,
                    LocalStickerOutline provides { id -> stickerOutline.load(id) },
                    LocalVideoPlayerPool provides videoPlayerPool,
                    LocalWebHttpClient provides webHttpClient,
                    LocalAvatarFileLoader provides { fileId, cd, modifier ->
                        TdMediaImage(
                            media = TdMedia(
                                fileId = fileId,
                                width = 0,
                                height = 0,
                                minithumbBytes = null,
                            ),
                            contentDescription = cd,
                            modifier = modifier,
                            placeholderColor = null,
                            showProgress = false,
                            priority = DownloadPriority.Avatar,
                        )
                    },
                    dev.lyo.hortay.LocalPlatformToaster provides
                        dev.lyo.hortay.AndroidToaster(applicationContext),
                    dev.lyo.hortay.ui.media.LocalMediaShareActions provides
                        dev.lyo.hortay.ui.media.AndroidMediaShareActions,
                    dev.lyo.hortay.ui.settings.LocalLanguagePicker provides
                        dev.lyo.hortay.ui.settings.AndroidLanguagePicker(this@MainActivity),
                    LocalGuestReportDelegate provides
                        { username, postId ->
                            when (guestReportDelegatorCompose.report(username, postId)) {
                                GuestReportDelegator.Outcome.OpenedTelegram ->
                                    GuestReportOutcome.OpenedTelegram
                                GuestReportDelegator.Outcome.OpenedWeb ->
                                    GuestReportOutcome.OpenedWeb
                                GuestReportDelegator.Outcome.OpenedEmail ->
                                    GuestReportOutcome.OpenedEmail
                                GuestReportDelegator.Outcome.AllFailed ->
                                    GuestReportOutcome.AllFailed
                            }
                        },
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val auth by tdClient.authStage.collectAsStateWithLifecycle()
                        val isGuest by guestMode.isGuest.collectAsStateWithLifecycle(
                            initialValue = false,
                        )
                        // Routing precedence:
                        //   1. Authenticated (auth.Ready) → full TDLib UI. The user signed
                        //      in; show them everything regardless of any earlier guest
                        //      preference.
                        //   2. Guest-mode flag set → web-only UI. The user explicitly
                        //      chose to read without signing in; honour that across cold
                        //      starts even though TDLib's auth state is "WaitPhone" by
                        //      default.
                        //   3. Otherwise → AuthScreen, where the user can either sign in
                        //      or flip the guest-mode flag.
                        when {
                            auth == AuthStage.Ready -> MediaViewerHost {
                                MainScaffold(
                                    feed = postsRepository,
                                    backend = backend,
                                    bookmarks = bookmarks,
                                    ignoredChannels = ignoredChannels,
                                    guestMode = guestMode,
                                    userMessages = userMessages,
                                    linkDialogs = linkDialogs,
                                    deepLinkRouter = deepLinkRouterCompose,
                                    startupPhase = startupCoordinator.phase,
                                    nav = nav,
                                    appScope = appScopeCompose,
                                    chatReadCursors = postsRepository.chatReadCursors,
                                )
                            }
                            // Guest mode also needs MediaViewerHost: TimelineScreen reads
                            // LocalMediaViewer to open photo/video previews on tap, and
                            // PostCard's full media-rendering chain assumes the host is
                            // present. Without this wrap the first measure pass crashes.
                            isGuest -> MediaViewerHost {
                                WebModeScaffold(
                                    backend = backend,
                                    bookmarks = bookmarks,
                                    ignoredChannels = ignoredChannels,
                                    guestMode = guestMode,
                                    webSubscriptions = webSubscriptions,
                                    webFeedSource = webFeedSource,
                                    webRepository = webRepository,
                                    webClient = webClient,
                                    settingsStore = settingsStore,
                                    linkDialogs = linkDialogs,
                                    deepLinkRouter = deepLinkRouterCompose,
                                    nav = nav,
                                    appScope = appScopeCompose,
                                )
                            }
                            else -> AuthScreen(
                                backend = backend,
                                guestMode = guestMode,
                                scope = appScopeCompose,
                                stage = auth,
                            )
                        }

                        // One-time post-sign-in migration proposal. Renders ON TOP of the
                        // authenticated UI when [MigrationCoordinator] surfaces a pending
                        // candidate list. Self-dismisses when the user confirms / skips —
                        // the coordinator persists "shown" so it doesn't reappear next
                        // session.
                        if (auth == AuthStage.Ready) {
                            val proposal by migrationCoordinator.pendingProposal
                                .collectAsStateWithLifecycle()
                            proposal?.let { candidates ->
                                MigrationProposalSheet(
                                    coordinator = migrationCoordinator,
                                    candidates = candidates,
                                    onDismiss = { /* coordinator clears pendingProposal */ },
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop / re-entry path: a fresh tg:// or https://t.me URL arriving while the
        // activity is already alive. The router's Channel.BUFFERED queue holds rapid-fire
        // links during a transition until MainScaffold's collector drains them in order.
        intent.data?.let { uri ->
            appScope.launch {
                linkResolver.resolve(uri)?.let { deepLinkRouter.submit(it) }
            }
        }
    }
}
