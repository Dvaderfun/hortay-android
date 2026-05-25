package dev.lyo.hortay.di

import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.ui.report.GuestReportDelegator
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android-side actuals for `platformModule()`. Hosts Context-bound singletons.
 *
 * `WebHttpCacheConfig` points the shared Ktor HttpClient at `cacheDir/web-http/`
 * for ETag-aware OkHttp caching. `GuestReportDelegator` is the tg:// →
 * CustomTabsIntent → mailto: delegation chain that fires for guest-mode
 * reports — wired only on Android because iOS has no Telegram-handler /
 * Custom Tabs equivalent.
 *
 * AndroidMediaShareActions / AndroidLanguagePicker stay direct
 * CompositionLocal providers in MainActivity (Activity-scoped, not
 * process-scoped — Koin would force them through an Activity-scope wrapper
 * for no win).
 */
actual fun platformModule(): Module = module {
    single { WebHttpCacheConfig(cacheRootPath = androidContext().cacheDir.absolutePath) }
    single {
        GuestReportDelegator(
            context = androidContext(),
            log = get<ReportLogStore>(),
            logScope = get<CoroutineScope>(),
        )
    }
}
