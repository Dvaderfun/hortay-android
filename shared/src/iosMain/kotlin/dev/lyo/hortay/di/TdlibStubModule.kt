package dev.lyo.hortay.di

import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.ui.media.VideoPlayerPool
import org.koin.dsl.module

/**
 * iOS stub mirror of `tdlibModule`. Each entry uses the no-arg stub
 * constructor that already lives in iosMain (`HortayBackend(...)` stub-arity,
 * `MediaCache()`, `CustomEmojiRepository()`, `VideoPlayerPool()`). Guest-mode
 * UI never calls into the TDLib-bound surface, so these stay no-ops.
 *
 * Phase II swaps this module for the real TDLib-on-iOS bindings once
 * `libtdjni.xcframework` lands.
 */
val tdlibStubModule = module {
    single { MediaCache() }
    single { CustomEmojiRepository() }
    single { VideoPlayerPool() }
    single {
        HortayBackend(
            settingsStore = get<SettingsStore>(),
            reportLogStore = get<ReportLogStore>(),
            reportExplainerStore = get<ReportExplainerStore>(),
        )
    }
}
