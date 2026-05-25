package dev.lyo.hortay.di

import org.koin.dsl.module

/**
 * Process-singletons that bridge UI to data: `LogoutCleanup` (eager-start;
 * subscribes to `tdClient.loggedOut` and orchestrates the existing
 * `runLogoutCleanup` fan-out previously inline in `AppGraph.init`).
 *
 * Empty in Wave 1 — gets populated in Wave 2.
 */
val uiBridgeModule = module {
}
