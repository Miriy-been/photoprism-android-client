package ua.com.radiokot.photoprism.features.sync

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import ua.com.radiokot.photoprism.base.util.ConnectivityChecker
import ua.com.radiokot.photoprism.db.AppDatabase
import ua.com.radiokot.photoprism.di.APP_NO_BACKUP_PREFERENCES
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncFolderDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncHistoryItemDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncPreferencesOnPrefs
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import ua.com.radiokot.photoprism.features.sync.logic.CancelSyncUseCase
import ua.com.radiokot.photoprism.features.sync.logic.ScanLocalFoldersUseCase
import ua.com.radiokot.photoprism.features.sync.logic.ScheduleSyncUseCase
import ua.com.radiokot.photoprism.features.sync.view.SyncNotificationsManager
import ua.com.radiokot.photoprism.features.sync.view.model.SyncSettingsViewModel

val syncFeatureModule: Module = module {
    single {
        get<AppDatabase>().syncedFiles()
    } bind SyncedFileDao::class

    single {
        get<AppDatabase>().syncFolders()
    } bind SyncFolderDao::class

    single {
        get<AppDatabase>().syncHistory()
    } bind SyncHistoryItemDao::class

    single {
        SyncPreferencesOnPrefs(
            sharedPreferences = get(named(APP_NO_BACKUP_PREFERENCES)),
        )
    }

    single {
        ScanLocalFoldersUseCase(
            context = androidContext(),
        )
    }

    single {
        ScheduleSyncUseCase(
            workManager = get(),
            syncPreferences = get(),
        )
    }

    single {
        CancelSyncUseCase(
            workManager = get(),
        )
    }

    single {
        SyncNotificationsManager(
            context = androidContext(),
        )
    }

    viewModel {
        SyncSettingsViewModel(
            scanLocalFoldersUseCase = get(),
            syncedFileDao = get(),
            syncFolderDao = get(),
            syncHistoryDao = get(),
            syncPreferences = get(),
            scheduleSyncUseCase = get(),
            workManager = get(),
            connectivityChecker = get(),
        )
    }
}
