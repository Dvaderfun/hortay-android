package dev.lyo.hortay.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS-side actuals for `platformModule()`. Mostly empty for now — iOS guest
 * mode skips the delegation chain entirely (no Telegram-handler / Custom
 * Tabs API; `MainViewController` wires `LocalGuestReportDelegate` directly
 * to `GuestReportOutcome.AllFailed`).
 *
 * `WebHttpCacheConfig` is `null` — Darwin engine uses URLCache's default
 * location.
 */
actual fun platformModule(): Module = module {
    single { WebHttpCacheConfig(cacheRootPath = null) }
}
