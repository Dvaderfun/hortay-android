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
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalVideoPlayerPool
import dev.lyo.hortay.ui.media.LocalWebHttpClient
import dev.lyo.hortay.ui.report.GuestReportOutcome
import dev.lyo.hortay.ui.report.LocalGuestReportDelegate
import dev.lyo.hortay.ui.web.WebModeScaffold
import platform.UIKit.UIViewController

/**
 * iOS entry point. Mounts the shared [WebModeScaffold] — the same composable
 * the Android guest-mode path renders — so the iOS app reaches feature parity
 * with the web-mode surface (full PostCard chrome, channels tab, bookmarks,
 * settings, predictive back, deep-link nudges).
 *
 * Phase II will replace the stub `HortayBackend` with a TDLib-on-iOS impl
 * once `libtdjni` gets cross-compiled.
 */
private val sharedGraph by lazy { IosAppGraph() }

@Suppress("FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    setSingletonImageLoaderFactory { ctx -> buildImageLoader(ctx) }
    HortayIosTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(
                LocalMediaCache provides sharedGraph.mediaCache,
                LocalCustomEmoji provides sharedGraph.customEmoji,
                LocalVideoPlayerPool provides sharedGraph.videoPlayerPool,
                LocalWebHttpClient provides sharedGraph.webHttpClient,
                // iOS guest mode never reaches the guest-report flow (no Telegram
                // client to launch, no Custom Tabs API yet); surface the manual-
                // instruction dialog by returning AllFailed.
                LocalGuestReportDelegate provides { _, _ -> GuestReportOutcome.AllFailed },
            ) {
                WebModeScaffold(
                    backend = sharedGraph.backend,
                    bookmarks = sharedGraph.bookmarks,
                    ignoredChannels = sharedGraph.ignoredChannels,
                    guestMode = sharedGraph.guestMode,
                    webSubscriptions = sharedGraph.subscriptions,
                    webFeedSource = sharedGraph.webFeedSource,
                    webRepository = sharedGraph.webRepository,
                    webClient = sharedGraph.webClient,
                    settingsStore = sharedGraph.settingsStore,
                    linkDialogs = sharedGraph.linkDialogs,
                    deepLinkRouter = sharedGraph.deepLinkRouter,
                    nav = sharedGraph.nav,
                    appScope = sharedGraph.appScope,
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
