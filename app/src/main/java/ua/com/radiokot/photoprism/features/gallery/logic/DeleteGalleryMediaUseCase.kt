package ua.com.radiokot.photoprism.features.gallery.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinItem

class DeleteGalleryMediaUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {

    /**
     * @param currentGalleryMediaRepository repository currently used to present the gallery,
     * which will be updated on successful archiving.
     */
    operator fun invoke(
        mediaUids: Collection<String>,
        currentGalleryMediaRepository: SimpleGalleryMediaRepository,
    ): Completable =
        currentGalleryMediaRepository.archive(itemUids = mediaUids)
            .andThen(
                Completable.defer {
                    val now = System.currentTimeMillis()
                    val items = currentGalleryMediaRepository.itemsList
                    mediaUids.forEach { uid ->
                        val media = items.find { it.uid == uid }
                        recycleBinDao.insert(
                            RecycleBinItem(
                                photoUid = uid,
                                archivedAt = now,
                                thumbnailHash = media?.hash,
                                photoTitle = media?.title,
                            )
                        )
                    }
                    Completable.complete()
                }.subscribeOn(Schedulers.io())
            )
}