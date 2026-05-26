package dev.lyo.hortay.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin

/**
 * iOS Koin entry point. Called from `MainViewController` (and exported to Swift
 * once the Phase II auth flow needs to drive DI from the SwiftUI host).
 *
 * Idempotent in practice — `MainViewController` calls it through a `lazy`
 * delegate so multiple ComposeUIViewController re-mounts share the same
 * container.
 *
 * Single [tdlibIosModule] for both device + simulator — the cinterop seam
 * lives one level down in [dev.lyo.hortay.tdlib.TdJsonClient]'s expect/actual.
 */
fun initKoin(): KoinApplication = startKoin {
    modules(
        coreModule,
        storesModule,
        webModule,
        tdlibIosModule,
        uiBridgeModule,
        viewModelModule,
        platformModule(),
    )
}
