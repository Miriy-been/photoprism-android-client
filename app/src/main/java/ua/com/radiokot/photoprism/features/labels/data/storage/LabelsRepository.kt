package ua.com.radiokot.photoprism.features.labels.data.storage

import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.api.labels.service.PhotoPrismLabelsService
import ua.com.radiokot.photoprism.base.data.model.DataPage
import ua.com.radiokot.photoprism.base.data.storage.SimpleCollectionRepository
import ua.com.radiokot.photoprism.extension.toSingle
import ua.com.radiokot.photoprism.features.labels.data.model.Label
import ua.com.radiokot.photoprism.features.labels.data.model.LabelCacheEntity
import ua.com.radiokot.photoprism.features.labels.data.storage.LabelCacheDao
import ua.com.radiokot.photoprism.util.PagedCollectionLoader

class LabelsRepository(
    private val isAllLabels: Boolean,
    private val photoPrismLabelsService: PhotoPrismLabelsService,
    private val labelCacheDao: LabelCacheDao,
) : SimpleCollectionRepository<Label>() {

    override fun getCollection(): Single<List<Label>> {
        val loader = PagedCollectionLoader(
            pageProvider = { cursor ->
                {
                    val offset = cursor?.toInt() ?: 0

                    val items = photoPrismLabelsService.getLabels(
                        count = PAGE_LIMIT,
                        offset = offset,
                        all = isAllLabels,
                    )

                    DataPage(
                        items = items,
                        nextCursor = (PAGE_LIMIT + offset).toString(),
                        isLast = items.size < PAGE_LIMIT,
                    )
                }.toSingle()
            }
        )

        return loader
            .loadAll()
            .map { photoPrismLabels ->
                photoPrismLabels.map(::Label)
            }
            .doOnSuccess { labels ->
                runBlocking {
                    val entities = labels.map { label ->
                        LabelCacheEntity(
                            uid = label.uid,
                            name = label.name,
                            slug = label.slug,
                            isFavorite = label.isFavorite,
                            itemCount = label.itemCount,
                            thumbnailHash = label.thumbnailHash.takeIf { it.isNotEmpty() },
                            cachedAt = System.currentTimeMillis(),
                        )
                    }
                    labelCacheDao.upsertAll(entities)
                }
            }
            .onErrorResumeNext { error ->
                runBlocking {
                    val cached = labelCacheDao.getAll()
                    if (cached.isNotEmpty()) {
                        Single.just(cached.map { entity ->
                            Label(
                                uid = entity.uid,
                                name = entity.name,
                                isFavorite = entity.isFavorite,
                                slug = entity.slug,
                                itemCount = entity.itemCount,
                                thumbnailHash = entity.thumbnailHash ?: "",
                            )
                        })
                    } else {
                        Single.error(error)
                    }
                }
            }
    }

    class Factory(
        private val photoPrismLabelsService: PhotoPrismLabelsService,
        private val labelCacheDao: LabelCacheDao,
    ) {
        private val cache: MutableMap<Boolean, LabelsRepository> = mutableMapOf()

        fun get(
            isAllLabels: Boolean,
        ) = cache.getOrPut(isAllLabels) {
            LabelsRepository(
                isAllLabels = isAllLabels,
                photoPrismLabelsService = photoPrismLabelsService,
                labelCacheDao = labelCacheDao,
            )
        }
    }

    private companion object {
        private const val PAGE_LIMIT = 120
    }
}
