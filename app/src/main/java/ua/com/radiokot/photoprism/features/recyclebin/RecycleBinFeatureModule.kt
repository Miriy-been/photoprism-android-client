package ua.com.radiokot.photoprism.features.recyclebin

import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import ua.com.radiokot.photoprism.di.APP_NO_BACKUP_PREFERENCES
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinPreferences
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinPreferencesOnPrefs

val recycleBinFeatureModule = module {
    single {
        RecycleBinPreferencesOnPrefs(
            preferences = get(named(APP_NO_BACKUP_PREFERENCES)),
        )
    } bind RecycleBinPreferences::class
}