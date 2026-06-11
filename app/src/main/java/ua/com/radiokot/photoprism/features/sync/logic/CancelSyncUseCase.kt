package ua.com.radiokot.photoprism.features.sync.logic

import androidx.work.WorkManager
import ua.com.radiokot.photoprism.extension.kLogger

class CancelSyncUseCase(
    private val workManager: WorkManager,
) {
    private val log = kLogger("CancelSyncUC")

    operator fun invoke() {
        workManager.cancelUniqueWork(SyncWorker.PERIODIC_TAG)
        log.debug { "invoke(): cancelled_periodic_sync" }
    }
}
