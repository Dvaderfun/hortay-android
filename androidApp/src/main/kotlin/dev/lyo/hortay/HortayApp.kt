package dev.lyo.hortay

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dev.lyo.hortay.app.BuildConfig
import dev.lyo.hortay.data.LocaleStore
import dev.lyo.hortay.di.coreModule
import dev.lyo.hortay.di.platformModule
import dev.lyo.hortay.di.storesModule
import dev.lyo.hortay.di.tdlibModule
import dev.lyo.hortay.di.uiBridgeModule
import dev.lyo.hortay.di.viewModelModule
import dev.lyo.hortay.di.webModule
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.web.WebRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.mp.KoinPlatform

class HortayApp : Application(), SingletonImageLoader.Factory {

    private val appScope: CoroutineScope by inject()
    private val webRepository: WebRepository by inject()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleStore.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // PlatformContextHolder must init BEFORE Koin starts — the KMP
        // createPreferencesDataStore factory reads its app context to
        // resolve filesDir for the .preferences_pb on-disk file. Stores
        // module instantiates DataStores at startKoin time (eager
        // singletons depend on them).
        dev.lyo.hortay.data.PlatformContextHolder.init(this)
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.INFO else Level.ERROR)
            androidContext(this@HortayApp)
            modules(
                coreModule,
                storesModule,
                webModule,
                tdlibModule,
                uiBridgeModule,
                viewModelModule,
                platformModule(),
            )
        }
        // Force eager singletons to materialise + side-effect-bind (tdClient,
        // lifecycleBridge, autoDownloader, webFeedScheduler, webCustomEmojiBridge,
        // migrationCoordinator, logoutCleanup). Koin guarantees createdAtStart
        // bindings instantiate in module-declaration order.
        KoinPlatform.getKoin().get<PostsRepository>()

        // Pre-warm the web DB on a background thread. SQLDelight's
        // AndroidSqliteDriver lazy-opens its SupportSQLiteOpenHelper on first
        // query — without this touch the schema creation, WAL switch and
        // PRAGMA setup would all run synchronously inside the first
        // observeFeed() collector on the main thread. A no-op SELECT here
        // moves the one-time ~50-100 ms cost off the critical path.
        appScope.launch { webRepository.subscribedUsernames() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .memoryCache {
            // 10% of Java heap leaves headroom for our MutableStateFlow snapshots, the
            // PersistentList feed, TDLib's working set and Compose buffers. 20% defaults
            // were too generous on low-end devices (256 MB heap → 50 MB cache alone).
            MemoryCache.Builder()
                .maxSizePercent(context, percent = 0.10)
                .build()
        }
        .diskCache {
            // 2% of free disk on a typical mid-range phone (32-128 GB) lands in the
            // 50-250 MB range, which fits well alongside TDLib's 500 MB media cap.
            // Clamp to [32 MB, 256 MB] so a 1 TB device doesn't dedicate gigabytes
            // to web-mode thumbs and a 16 GB device doesn't shrink below a usable
            // working set. Hortay's primary image pipeline is TDLib (drawn directly
            // from minithumb byte arrays + on-disk file paths, with diskCachePolicy
            // explicitly DISABLED in TdMediaImage), so Coil's disk cache mainly
            // serves web-mode (`t.me/s/<u>` thumbs) and channel avatars rendered via
            // AsyncImage outside the TDLib path.
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil"))
                .minimumMaxSizeBytes(32L * 1024 * 1024)
                .maxSizePercent(0.02)
                .maximumMaxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()
}
