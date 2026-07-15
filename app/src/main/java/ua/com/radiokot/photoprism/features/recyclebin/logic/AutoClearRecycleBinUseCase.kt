package ua.com.radiokot.photoprism.features.recyclebin.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.api.photos.model.PhotoPrismBatchPhotoUids
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao

class AutoClearRecycleBinUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {
    private val log = kLogger("AutoClearRecycleBinUC")

    /**
     * @param retentionDays items older than this many days will be permanently deleted.
     * Pass null or 0 to skip clearing (disabled).
     */
    operator fun invoke(retentionDays: Int?): Completable {
        if (retentionDays == null || retentionDays <= 0) {
            log.debug { "invoke(): auto_clear_disabled_skipping" }
            return Completable.complete()
        }

        return Completable.defer {
            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
            val expiredItems = runBlocking {
                recycleBinDao.getExpiredItems(cutoff)
            }

            if (expiredItems.isEmpty()) {
                log.debug { "invoke(): no_expired_items_found" }
                return@defer Completable.complete()
            }

            val expiredUids = expiredItems.map { it.photoUid }
            log.debug {
                "invoke(): clearing_expired_items:" +
                        "\ncount=${expiredUids.size}," +
                        "\nretentionDays=$retentionDays"
            }

            photoPrismPhotosService.batchDelete(
                PhotoPrismBatchPhotoUids(expiredUids)
            )
            runBlocking {
                recycleBinDao.deleteExpired(cutoff)
            }
            Completable.complete()
        }
            .subscribeOn(Schedulers.io())
            .doOnComplete {
                log.debug { "invoke(): auto_clear_completed" }
            }
    }
}