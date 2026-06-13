package ua.com.radiokot.photoprism.features.people.data.storage

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.api.faces.service.PhotoPrismFacesService
import ua.com.radiokot.photoprism.api.subjects.service.PhotoPrismSubjectsService
import ua.com.radiokot.photoprism.base.data.model.DataPage
import ua.com.radiokot.photoprism.base.data.storage.SimpleCollectionRepository
import ua.com.radiokot.photoprism.extension.toSingle
import ua.com.radiokot.photoprism.features.people.data.model.PeopleCacheEntity
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.features.people.data.storage.PeopleCacheDao
import ua.com.radiokot.photoprism.util.PagedCollectionLoader

/**
 * A repository for people which the gallery content can be filtered by.
 *
 * Combined from person subjects (People Recognized) and unknown faces (People New).
 *
 * First go favorite named people.
 */
class PeopleRepository(
    private val photoPrismSubjectsService: PhotoPrismSubjectsService,
    private val photoPrismFacesService: PhotoPrismFacesService,
    private val peopleCacheDao: PeopleCacheDao,
) : SimpleCollectionRepository<Person>() {
    private val comparator =
        compareByDescending(Person::isFavorite)
            .thenByDescending(Person::hasName)
            .thenByDescending(Person::photoCount)
            .thenBy(Person::name)

    override fun getCollection(): Single<List<Person>> =
        Single.mergeDelayError(
            getFromPersonSubjects(),
            getFromUnknownFaces(),
        )
            .collectInto(mutableListOf<Person>()) { collectedPeople, people ->
                collectedPeople.addAll(people)
            }
            .map { it.sortedWith(comparator) }
            .doOnSuccess { people ->
                runBlocking {
                    val entities = people.map { person ->
                        PeopleCacheEntity(
                            id = person.id,
                            name = person.name,
                            isFavorite = person.isFavorite,
                            photoCount = person.photoCount,
                            thumbnailHash = person.thumbnailHash.takeIf { it.isNotEmpty() },
                            cachedAt = System.currentTimeMillis(),
                        )
                    }
                    peopleCacheDao.upsertAll(entities)
                }
            }
            .onErrorResumeNext { error ->
                runBlocking {
                    val cached = peopleCacheDao.getAll()
                    if (cached.isNotEmpty()) {
                        Single.just(cached.map { entity ->
                            Person(
                                name = entity.name,
                                id = entity.id,
                                isFavorite = entity.isFavorite,
                                isFace = false,
                                photoCount = entity.photoCount,
                                thumbnailHash = entity.thumbnailHash ?: "",
                            )
                        })
                    } else {
                        Single.error(error)
                    }
                }
            }

    private fun getFromPersonSubjects(): Single<List<Person>> {
        val loader = PagedCollectionLoader(
            pageProvider = { cursor ->
                {
                    val offset = cursor?.toInt() ?: 0

                    val items = photoPrismSubjectsService.getSubjects(
                        count = PAGE_LIMIT,
                        offset = offset,
                        type = "person"
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
            .map { personSubjects ->
                personSubjects.map(::Person)
            }
            .subscribeOn(Schedulers.io())
    }

    private fun getFromUnknownFaces(): Single<List<Person>> {
        val loader = PagedCollectionLoader(
            pageProvider = { cursor ->
                {
                    val offset = cursor?.toInt() ?: 0

                    val items = photoPrismFacesService.getFaces(
                        count = PAGE_LIMIT,
                        offset = offset,
                        markers = true,
                        unknown = true,
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
            .map { unknownFaces ->
                unknownFaces.map(::Person)
            }
    }

    /**
     * @return [Person] found by [uid] in the [itemsList]
     * or null if nothing found.
     */
    fun getLoadedPerson(uid: String): Person? =
        itemsList.find { it.id == uid }

    private companion object {
        private const val PAGE_LIMIT = 30
    }
}
