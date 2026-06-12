package ua.com.radiokot.photoprism.features.sync.logic

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncPreferencesOnPrefs
import java.util.concurrent.TimeUnit

class ScheduleSyncUseCase(
    private val workManager: WorkManager,
    private val syncPreferences: SyncPreferencesOnPrefs,
) {
    private val log = kLogger("ScheduleSyncUC")

    operator fun invoke() = with(workManager) {
        val request = buildRequest()

        enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )

        log.debug { "invoke(): scheduled_periodic_sync" }
    }

    private fun buildRequest(): androidx.work.PeriodicWorkRequest {
        val wifiOnly = syncPreferences.wifiOnly.value ?: true
        val intervalMin = syncPreferences.syncIntervalMin.value
            ?: SyncPreferencesOnPrefs.INTERVAL_2_HOURS

        val constraints = if (wifiOnly) {
            Constraints.Builder()
                .setRequiredNetworkRequest(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    NetworkType.UNMETERED,
                )
                .build()
        } else {
            Constraints.Builder()
                .setRequiredNetworkRequest(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    NetworkType.CONNECTED,
                )
                .build()
        }

        return PeriodicWorkRequestBuilder<SyncWorker>(intervalMin.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(SyncWorker.PERIODIC_TAG)
            .build()
    }
}
