package dev.lyo.hortay.di

import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.data.archive.ArchiveSettingsStore
import dev.lyo.hortay.data.archive.ArchiveSweep
import dev.lyo.hortay.data.archive.ArchivedMediaStore
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.archive.db.ArchiveDatabaseProvider
import dev.lyo.hortay.data.createPreferencesDataStore
import dev.lyo.hortay.ui.archive.ArchiveSettingsViewModel
import dev.lyo.hortay.ui.archive.ArchiveViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Post-archive DI. commonMain so iOS guest mode gets the same archive DB + repository (its capture
 * runs through the web path; the TDLib capture hooks are androidMain). The platform
 * [ArchivedMediaStore] (file copy) is resolved with `getOrNull()` — provided in androidMain's
 * `tdlibModule`, null on iOS, in which case capture records the structured media ref + minithumb
 * but copies no file (the revision sheet falls back to the minithumb / re-download path).
 *
 * The settings [StateFlow] is named so it doesn't collide with other `StateFlow<*>` singles under
 * Koin's KClass-erased keying.
 */
val archiveModule = module {
    single { ArchiveDatabaseProvider.create() }

    single { ArchiveSettingsStore(createPreferencesDataStore(ArchiveSettingsStore.FILE_NAME)) }

    single<StateFlow<ArchiveSettings>>(named(ARCHIVE_SETTINGS_FLOW)) {
        get<ArchiveSettingsStore>().flow
            .stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, ArchiveSettings.DEFAULT)
    }

    single {
        ArchiveRepository(
            db = get<ArchiveDatabase>(),
            settings = get(named(ARCHIVE_SETTINGS_FLOW)),
            mediaStore = getOrNull<ArchivedMediaStore>(),
            releaseScope = get<CoroutineScope>(),
        )
    }

    single {
        ArchiveSweep(
            db = get<ArchiveDatabase>(),
            settings = get(named(ARCHIVE_SETTINGS_FLOW)),
            mediaStore = getOrNull<ArchivedMediaStore>(),
        )
    }

    viewModel { ArchiveViewModel(repo = get()) }
    viewModel { ArchiveSettingsViewModel(store = get(), repo = get(), sweep = get()) }
}

internal const val ARCHIVE_SETTINGS_FLOW = "archiveSettingsFlow"
