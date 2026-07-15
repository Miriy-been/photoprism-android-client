package ua.com.radiokot.photoprism.features.recyclebin.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.photos.model.PhotoPrismBatchPhotoUids
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao

class RestoreGalleryMediaUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {
    private val log = kLogger("RestoreGalleryMediaUC")

    operator fun invoke(mediaUids: Collection<String>): Completable =
        Completable.defer {
            photoPrismPhotosService.batchRestore(
                PhotoPrismBatchPhotoUids(mediaUids)
            )
            Completable.complete()
        }
            .subscribeOn(Schedulers.io())
            .doOnComplete {
                recycleBinDao.deleteByUids(mediaUids)
                log.debug {
                    "invoke(): restored_and_removed_from_local:" +
                            "\nitems=${mediaUids.size}"
                }
            }
}