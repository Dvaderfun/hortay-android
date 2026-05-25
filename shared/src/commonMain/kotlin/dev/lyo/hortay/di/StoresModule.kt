package dev.lyo.hortay.di

import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.createPreferencesDataStore
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.data.web.MigrationStore
import dev.lyo.hortay.data.web.SubscriptionsStore
import org.koin.dsl.module

/**
 * DataStore-backed stores. Every entry uses the KMP
 * [createPreferencesDataStore] factory from `data/PreferencesDataStoreFactory.kt`
 * (Okio-backed Preferences DataStore — Android stores under `app_filesDir`,
 * iOS under `NSDocumentDirectory`). The FILE_NAME constants on each class
 * stay the canonical key; bumping one rotates the on-disk file.
 *
 * Cross-mode contract: every store in this module is alive in BOTH
 * authenticated mode (AppGraph) and guest mode (IosAppGraph), so each lives
 * here in commonMain rather than `tdlibModule` / `tdlibStubModule`.
 *
 * `ReportLogStore` has a no-arg constructor that resolves the JSONL log file
 * via the same KMP `applicationFilesPath(fileName)` helper used by other
 * file-backed stores — single source of truth for the audit log location.
 */
val storesModule = module {
    single { SettingsStore(createPreferencesDataStore(SettingsStore.FILE_NAME)) }
    single { IgnoredChannelsStore(createPreferencesDataStore(IgnoredChannelsStore.FILE_NAME)) }
    single { BookmarkStore(createPreferencesDataStore(BookmarkStore.FILE_NAME)) }
    single { SubscriptionsStore(createPreferencesDataStore(SubscriptionsStore.FILE_NAME)) }
    single { GuestModeStore(createPreferencesDataStore(GuestModeStore.FILE_NAME)) }
    single { MigrationStore(createPreferencesDataStore(MigrationStore.FILE_NAME)) }
    single { ReportExplainerStore(createPreferencesDataStore(ReportExplainerStore.FILE_NAME)) }
    single { ReportLogStore() }
}
