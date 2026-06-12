package ua.com.radiokot.photoprism.features.sync.data.storage

import android.content.SharedPreferences
import io.reactivex.rxjava3.subjects.BehaviorSubject
import ua.com.radiokot.photoprism.R

class SyncPreferencesOnPrefs(
    private val sharedPreferences: SharedPreferences,
) {
    companion object {
        private const val KEY_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_LAST_FULL_SYNC_AT = "sync_last_full_sync_at"
        private const val KEY_SYNC_INTERVAL_MIN = "sync_interval_min"

        /** Manual sync only — no automatic periodic sync. */
        const val INTERVAL_MANUAL = 0
        const val INTERVAL_30_MIN = 30
        const val INTERVAL_1_HOUR = 60
        const val INTERVAL_2_HOURS = 120
        const val INTERVAL_4_HOURS = 240

        val INTERVAL_OPTIONS = listOf(
            INTERVAL_MANUAL,
            INTERVAL_30_MIN,
            INTERVAL_1_HOUR,
            INTERVAL_2_HOURS,
            INTERVAL_4_HOURS,
        )

        fun intervalLabelRes(intervalMin: Int): Int = when (intervalMin) {
            INTERVAL_MANUAL -> R.string.sync_interval_manual
            INTERVAL_30_MIN -> R.string.sync_interval_30min
            INTERVAL_1_HOUR -> R.string.sync_interval_1h
            INTERVAL_2_HOURS -> R.string.sync_interval_2h
            INTERVAL_4_HOURS -> R.string.sync_interval_4h
            else -> R.string.sync_interval_2h
        }
    }

    val wifiOnly: BehaviorSubject<Boolean> = BehaviorSubject.createDefault(
        sharedPreferences.getBoolean(KEY_WIFI_ONLY, true)
    )

    val lastFullSyncAt: BehaviorSubject<Long> = BehaviorSubject.createDefault(
        sharedPreferences.getLong(KEY_LAST_FULL_SYNC_AT, 0L)
    )

    val syncIntervalMin: BehaviorSubject<Int> = BehaviorSubject.createDefault(
        sharedPreferences.getInt(KEY_SYNC_INTERVAL_MIN, INTERVAL_2_HOURS)
    )

    val resyncCheckIntervalMs: Long
        get() = when (syncIntervalMin.value ?: INTERVAL_2_HOURS) {
            INTERVAL_MANUAL -> 0L
            else -> (syncIntervalMin.value ?: INTERVAL_2_HOURS).toLong() * 60 * 1000
        }

    fun setWifiOnly(value: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        wifiOnly.onNext(value)
    }

    fun setLastFullSyncAt(value: Long) {
        sharedPreferences.edit().putLong(KEY_LAST_FULL_SYNC_AT, value).apply()
        lastFullSyncAt.onNext(value)
    }

    fun setSyncIntervalMin(value: Int) {
        sharedPreferences.edit().putInt(KEY_SYNC_INTERVAL_MIN, value).apply()
        syncIntervalMin.onNext(value)
    }
}
