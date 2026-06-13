package ua.com.radiokot.photoprism.features.albums.data.storage

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.toCompletable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.api.albums.model.PhotoPrismAlbumCreation
import ua.com.radiokot.photoprism.api.albums.service.PhotoPrismAlbumsService
import ua.com.radiokot.photoprism.api.photos.model.PhotoPrismBatchPhotoUids
import ua.com.radiokot.photoprism.base.data.model.DataPage
import ua.com.radiokot.photoprism.base.data.storage.SimpleCollectionRepository
import ua.com.radiokot.photoprism.db.AlbumCacheDao
import ua.com.radiokot.photoprism.db.AlbumCacheEntity
import ua.com.radiokot.photoprism.extension.toSingle
import ua.com.radiokot.photoprism.features.albums.data.model.Album
import ua.com.radiokot.photoprism.util.PagedCollectionLoader

/**
 * A repository for albums, which can be folders, actual albums, months, etc.
 * Combines albums of multiple [types].
 */
class AlbumsRepository(
    private val types: Set<Album.TypeName>,
    private val photoPrismAlbumsService: PhotoPrismAlbumsService,
    private val albumCacheDao: AlbumCacheDao,
) : SimpleCollectionRepository<Album>() {
    override fun getCollection(): Single<List<Album>> =
        Single.mergeDelayError(types.map(::getAlbumsOfType))
            .collectInto(mutableListOf<Album>()) { collectedAlbums, albums ->
                collectedAlbums.addAll(albums)
            }
            .map(MutableList<Album>::toList)
            .doOnSuccess { albums ->
                runBlocking {
                    types.forEach { type ->
                        val entities = albums
                            .filter { it.type == type }
                            .mapIndexed { index, album ->
                                AlbumCacheEntity(
                                    uid = album.uid,
                                    type = type.name.lowercase(),
                                    title = album.title,
                                    thumbnailHash = album.thumbnailHash.takeIf { it.isNotEmpty() },
                                    photoCount = albums.count { it.type == type },
                                    path = album.path,
                                    ymd = album.ymd.takeIf { it != Album.YMD_UNSPECIFIED },
                                    cachedAt = System.currentTimeMillis(),
                                )
                            }
                        albumCacheDao.deleteByType(type.name.lowercase())
                        albumCacheDao.upsertAll(entities)
                    }
                }
            }
            .onErrorResumeNext { error ->
                runBlocking {
                    val cachedByType = types.mapNotNull { type ->
                        val entities = albumCacheDao.getByType(type.name.lowercase())
                        if (entities.isNotEmpty()) type to entities else null
                    }
                    val allCached = cachedByType.flatMap { (type, entities) ->
                        entities.map { entity ->
                            Album(
                                type = type,
                                title = entity.title,
                                path = entity.path,
                                uid = entity.uid,
                                isFavorite = false,
                                ymd = entity.ymd ?: Album.YMD_UNSPECIFIED,
                                thumbnailHash = entity.thumbnailHash ?: "",
                            )
                        }
                    }
                    if (allCached.isNotEmpty()) {
                        Single.just(allCached)
                    } else {
                        Single.error(error)
                    }
                }
            }

    /**
     * @return [Album] found by [uid] in the [itemsList]
     * or null if nothing found.
     */
    fun getLoadedAlbum(uid: String): Album? =
        itemsList.find { it.uid == uid }

    private fun getAlbumsOfType(type: Album.TypeName): Single<List<Album>> {
        val loader = PagedCollectionLoader(
            pageProvider = { cursor ->
                {
                    val offset = cursor?.toInt() ?: 0

                    val items = photoPrismAlbumsService.getAlbums(
                        count = PAGE_LIMIT,
                        offset = offset,
                        type = type.value,
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
            .map { photoPrismAlbums ->
                photoPrismAlbums.map(::Album)
            }
            .subscribeOn(Schedulers.io())
    }

    fun create(
        title: String,
    ): Single<Album> = {
        photoPrismAlbumsService.createAlbum(
            PhotoPrismAlbumCreation(
                title = title,
            )
        )
            .let(::Album)
            .also { createdAlbum ->
                mutableItemsList.add(createdAlbum)
                broadcast()
            }
    }.toSingle().subscribeOn(Schedulers.io())

    fun addItemsToAlbum(
        albumUid: String,
        itemUids: Collection<String>,
    ): Completable = {
        photoPrismAlbumsService.addPhotos(
            albumUid = albumUid,
            batchPhotoUids = PhotoPrismBatchPhotoUids(itemUids),
        )

        // Invalidate as adding items may change the album thumb,
        // but unfortunately it is not updated instantly.
        invalidate()
    }.toCompletable().subscribeOn(Schedulers.io())

    fun removeItemsFromAlbum(
        albumUid: String,
        itemUids: Collection<String>,
    ): Completable = {
        photoPrismAlbumsService.deletePhotos(
            albumUid = albumUid,
            batchPhotoUids = PhotoPrismBatchPhotoUids(itemUids),
        )

        // Invalidate as removing items may change the album thumb,
        // but unfortunately it is not updated instantly.
        invalidate()
    }.toCompletable().subscribeOn(Schedulers.io())

    class Factory(
        private val photoPrismAlbumsService: PhotoPrismAlbumsService,
        private val albumCacheDao: AlbumCacheDao,
    ) {
        val albums: AlbumsRepository by lazy {
            AlbumsRepository(
                types = setOf(Album.TypeName.ALBUM),
                photoPrismAlbumsService = photoPrismAlbumsService,
                albumCacheDao = albumCacheDao,
            )
        }

        val folders: AlbumsRepository by lazy {
            AlbumsRepository(
                types = setOf(Album.TypeName.FOLDER),
                photoPrismAlbumsService = photoPrismAlbumsService,
                albumCacheDao = albumCacheDao,
            )
        }

        val months: AlbumsRepository by lazy {
            AlbumsRepository(
                types = setOf(Album.TypeName.MONTH),
                photoPrismAlbumsService = photoPrismAlbumsService,
                albumCacheDao = albumCacheDao,
            )
        }

        fun forType(type: Album.TypeName): AlbumsRepository = when (type) {
            Album.TypeName.ALBUM ->
                albums

            Album.TypeName.FOLDER ->
                folders

            Album.TypeName.MONTH ->
                months
        }
    }

    private companion object {
        private const val PAGE_LIMIT = 30
    }
}
