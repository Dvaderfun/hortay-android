package dev.lyo.hortay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.NavStack
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.di.initKoin
import dev.lyo.hortay.ui.auth.AuthScreen
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.ui.media.LocalAvatarFileLoader
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalVideoPlayerPool
import dev.lyo.hortay.ui.media.LocalWebHttpClient
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.VideoPlayerPool
import dev.lyo.hortay.ui.report.GuestReportOutcome
import dev.lyo.hortay.ui.report.LocalGuestReportDelegate
import dev.lyo.hortay.ui.web.WebModeScaffold
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import org.koin.compose.koinInject
import platform.UIKit.UIViewController

/**
 * iOS entry point.
 *
 * **Routing precedence** (mirrors Android's [dev.lyo.hortay.MainActivity]):
 *   1. `auth.Ready` → [SignedInPlaceholder]. The full [dev.lyo.hortay.ui.main.MainScaffold]
 *      mounts here once Phase II-D1 lifts the authenticated repositories
 *      (PostsRepository, CommentsRepository, …) to commonMain.
 *   2. `isGuest == true` → [WebModeScaffold]. Same composable Android guest
 *      mode mounts — full PostCard chrome, predictive back, deep-link nudges.
 *   3. `auth.Loading` → [AuthLoadingScreen]. Brief splash while TDLib walks
 *      its initial state machine.
 *   4. otherwise → [AuthScreen]. The sign-in / "Continue without account" form.
 *
 * On simulator builds the [dev.lyo.hortay.tdlib.TdJsonClient] stub emits a
 * synthetic `WaitPhoneNumber` at construction so the routing lands on
 * `AuthScreen`; from there the "Continue without account" button is the only
 * working path forward.
 */
private val koinApp by lazy { initKoin() }

@Suppress("FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    koinApp // touch to ensure startKoin ran before any koinInject call
    setSingletonImageLoaderFactory { ctx -> buildImageLoader(ctx) }
    HortayIosTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val mediaCache = koinInject<MediaCache>()
            val customEmoji = koinInject<CustomEmojiRepository>()
            val videoPlayerPool = koinInject<VideoPlayerPool>()
            val webHttpClient = koinInject<HttpClient>()
            val backend = koinInject<HortayBackend>()
            val bookmarks = koinInject<BookmarkStore>()
            val ignoredChannels = koinInject<IgnoredChannelsStore>()
            val guestMode = koinInject<GuestModeStore>()
            val webSubscriptions = koinInject<SubscriptionsStore>()
            val webFeedSource = koinInject<WebFeedSource>()
            val webRepository = koinInject<WebRepository>()
            val webClient = koinInject<WebTelegramClient>()
            val settingsStore = koinInject<SettingsStore>()
            val linkDialogs = koinInject<LinkDialogState>()
            val deepLinkRouter = koinInject<DeepLinkRouter>()
            val nav = koinInject<NavStack>()
            val appScope = koinInject<CoroutineScope>()
            val userMessages = koinInject<UserMessageBus>()
            val postsRepo = koinInject<dev.lyo.hortay.data.posts.PostsRepository>()
            val startupCoordinator = koinInject<StartupCoordinator>()

            CompositionLocalProvider(
                LocalMediaCache provides mediaCache,
                LocalCustomEmoji provides customEmoji,
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
                LocalGuestReportDelegate provides { _, _ -> GuestReportOutcome.AllFailed },
            ) {
              dev.lyo.hortay.ui.media.MediaViewerHost {
                val authStage by backend.authStage.collectAsStateWithLifecycle()
                val isGuest by guestMode.isGuest.collectAsStateWithLifecycle(initialValue = false)

                when {
                    authStage == AuthStage.Ready -> dev.lyo.hortay.ui.main.MainScaffold(
                        feed = postsRepo,
                        backend = backend,
                        bookmarks = bookmarks,
                        ignoredChannels = ignoredChannels,
                        guestMode = guestMode,
                        userMessages = userMessages,
                        linkDialogs = linkDialogs,
                        deepLinkRouter = deepLinkRouter,
                        startupPhase = startupCoordinator.phase,
                        nav = nav,
                        appScope = appScope,
                        chatReadCursors = postsRepo.chatReadCursors,
                    )
                    isGuest -> WebModeScaffold(
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
                        deepLinkRouter = deepLinkRouter,
                        nav = nav,
                        appScope = appScope,
                    )
                    authStage is AuthStage.Loading -> AuthLoadingScreen()
                    else -> AuthScreen(
                        backend = backend,
                        guestMode = guestMode,
                        scope = appScope,
                        stage = authStage,
                    )
                }
              }
            }
        }
    }
}

private fun buildImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        .crossfade(true)
        .build()

@Composable
private fun HortayIosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

/**
 * Brief splash shown while TDLib advances through `WaitTdlibParameters` on
 * cold start. Mirrors Android's `LoadingForm` posture inside the auth flow.
 */
@Composable
private fun AuthLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

/**
 * Temporary post-sign-in landing. The full authenticated feed lands once Phase
 * II-D1 lifts `PostsRepository` + `CommentsRepository` + folder / archive
 * infrastructure to commonMain.
 */
@Composable
private fun SignedInPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Signed in",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Feed wiring lands in the next phase.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
