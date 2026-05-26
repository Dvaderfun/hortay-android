package dev.lyo.hortay.di

import dev.lyo.hortay.data.ComposeResourcesStringResolver
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.data.NavStack
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.PlatformLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

/**
 * Process-singletons with no platform deps.
 *
 * `appScope` is a process-lifetime `CoroutineScope(SupervisorJob() +
 * Dispatchers.Default)`. Cancelled only at process death — every repository
 * coroutine launched from here outlives the Activity / SwiftUI host.
 *
 * `LinkDialogState`, `ReportDialogState`, `NavStack`, `DeepLinkRouter` are
 * hoisted to the graph because they outlive the scaffold (rotation / overlay
 * dismiss / deep-link routing). See each class's KDoc for the rationale.
 */
val coreModule = module {
    single<CoroutineScope> {
        val handler = CoroutineExceptionHandler { _, t ->
            PlatformLog.w("AppScope", "Unhandled coroutine exception: ${t::class.simpleName}: ${t.message}", t)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
    single { UserMessageBus() }
    single { LinkDialogState() }
    single { ReportDialogState() }
    single { DeepLinkRouter() }
    single { NavStack() }
    single<StringResolver> { ComposeResourcesStringResolver() }
}
