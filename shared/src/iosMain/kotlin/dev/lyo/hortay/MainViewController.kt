package dev.lyo.hortay

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.lyo.hortay.data.BookmarkStore
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
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalVideoPlayerPool
import dev.lyo.hortay.ui.media.LocalWebHttpClient
import dev.lyo.hortay.ui.media.VideoPlayerPool
import dev.lyo.hortay.ui.report.GuestReportOutcome
import dev.lyo.hortay.ui.report.LocalGuestReportDelegate
import dev.lyo.hortay.ui.web.WebModeScaffold
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import org.koin.compose.koinInject
import platform.UIKit.UIViewController

/**
 * iOS entry point. Mounts the shared [WebModeScaffold] — the same composable
 * the Android guest-mode path renders — so the iOS app reaches feature parity
 * with the web-mode surface (full PostCard chrome, channels tab, bookmarks,
 * settings, predictive back, deep-link nudges).
 *
 * Phase II will swap the stub `HortayBackend` (from `tdlibStubModule`) for a
 * real TDLib-on-iOS impl once `libtdjni.xcframework` lands; nothing else here
 * changes.
 */
private val koinApp by lazy { initKoin() }

@Suppress("FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    koinApp // touch to ensure startKoin ran before any koinInject call
    setSingletonImageLoaderFactory { ctx -> buildImageLoader(ctx) }
    HortayIosTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Resolve every shared singleton from Koin at the entry point;
            // inner composables keep their explicit params.
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

            CompositionLocalProvider(
                LocalMediaCache provides mediaCache,
                LocalCustomEmoji provides customEmoji,
                LocalVideoPlayerPool provides videoPlayerPool,
                LocalWebHttpClient provides webHttpClient,
                // iOS guest mode never reaches the guest-report flow (no Telegram
                // client to launch, no Custom Tabs API yet); surface the manual-
                // instruction dialog by returning AllFailed.
                LocalGuestReportDelegate provides { _, _ -> GuestReportOutcome.AllFailed },
            ) {
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
                    deepLinkRouter = deepLinkRouter,
                    nav = nav,
                    appScope = appScope,
                )
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
