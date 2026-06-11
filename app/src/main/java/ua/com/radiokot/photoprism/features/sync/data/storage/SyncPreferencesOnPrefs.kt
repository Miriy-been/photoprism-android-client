package ua.com.radiokot.photoprism.features.sync.data.storage

import android.content.SharedPreferences
import io.reactivex.rxjava3.subjects.BehaviorSubject

class SyncPreferencesOnPrefs(
    private val sharedPreferences: SharedPreferences,
) {
    companion object {
        private const val KEY_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_LAST_FULL_SYNC_AT = "sync_last_full_sync_at"
    }

    val wifiOnly: BehaviorSubject<Boolean> = BehaviorSubject.createDefault(
        sharedPreferences.getBoolean(KEY_WIFI_ONLY, true)
    )

    val lastFullSyncAt: BehaviorSubject<Long> = BehaviorSubject.createDefault(
        sharedPreferences.getLong(KEY_LAST_FULL_SYNC_AT, 0L)
    )

    fun setWifiOnly(value: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        wifiOnly.onNext(value)
    }

    fun setLastFullSyncAt(value: Long) {
        sharedPreferences.edit().putLong(KEY_LAST_FULL_SYNC_AT, value).apply()
        lastFullSyncAt.onNext(value)
    }
}
