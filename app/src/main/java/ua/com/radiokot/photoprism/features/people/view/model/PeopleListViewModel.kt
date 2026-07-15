package ua.com.radiokot.photoprism.features.people.view.model

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.api.subjects.model.PhotoPrismSubjectUpdate
import ua.com.radiokot.photoprism.api.subjects.service.PhotoPrismSubjectsService
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.logic.MediaPreviewUrlFactory
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.features.people.data.storage.PeopleRepository
import ua.com.radiokot.photoprism.features.shared.search.view.model.SearchViewViewModel
import ua.com.radiokot.photoprism.features.shared.search.view.model.SearchViewViewModelImpl

class PeopleListViewModel(
    private val peopleRepository: PeopleRepository,
    private val photoPrismSubjectsService: PhotoPrismSubjectsService,
    private val mediaPreviewUrlFactory: MediaPreviewUrlFactory,
    private val searchPredicate: (Person, String) -> Boolean = { person, query ->
        person.name?.contains(query, ignoreCase = true) == true
    },
) : ViewModel(), SearchViewViewModel by SearchViewViewModelImpl() {

    private val log = kLogger("PeopleListViewModel")
    private val disposables = CompositeDisposable()

    val itemsList = MutableLiveData<List<PersonListItem>>()
    val isLoading = MutableLiveData(false)
    val mainError = MutableLiveData<Error?>()

    private val eventsSubject = PublishSubject.create<Event>()
    val events = eventsSubject.observeOnMain()

    private var defaultSearchConfig: SearchConfig = SearchConfig.DEFAULT

    sealed interface Event {
        class OpenPersonDetail(
            val person: Person,
            val title: String,
            val repositoryParams: SimpleGalleryMediaRepository.Params,
        ) : Event

        class ShowRenameDialog(val person: Person) : Event

        object ShowFloatingLoadingFailedError : Event

        class ShowRenameSuccess(val personName: String) : Event

        object ShowRenameFailed : Event
    }

    sealed interface Error {
        object LoadingFailed : Error
        object NothingFound : Error
    }

    fun initOnce(defaultSearchConfig: SearchConfig) {
        this.defaultSearchConfig = defaultSearchConfig
        subscribeToRepository()
        subscribeToSearch()
        peopleRepository.update()
    }

    private fun subscribeToRepository() {
        peopleRepository.items
            .filter { !peopleRepository.isNeverUpdated }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { people ->
                updateItemsList(people)
                if (people.isEmpty()) {
                    mainError.value = Error.NothingFound
                }
            }
            .autoDispose(this)

        peopleRepository.errors
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { error ->
                log.error(error) { "subscribeToRepository(): people_loading_failed" }

                if (itemsList.value == null) {
                    mainError.value = Error.LoadingFailed
                } else {
                    eventsSubject.onNext(Event.ShowFloatingLoadingFailedError)
                }
            }
            .autoDispose(this)

        peopleRepository.loading
            .subscribe(isLoading::postValue)
            .autoDispose(this)
    }

    private fun subscribeToSearch() {
        searchInputObservable
            .filter { itemsList.value != null }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { updateItemsList(peopleRepository.itemsList) }
            .autoDispose(this)
    }

    fun onSwipeRefreshPulled() {
        mainError.value = null
        peopleRepository.update()
    }

    fun onRetryClicked() {
        mainError.value = null
        peopleRepository.update()
    }

    fun onPersonItemClicked(item: PersonListItem) {
        val person = item.person
        if (Person.isFaceId(person.id)) {
            // Unknown face, show rename dialog
            eventsSubject.onNext(Event.ShowRenameDialog(person))
        } else {
            // Known person, open detail
            eventsSubject.onNext(Event.OpenPersonDetail(
                person = person,
                title = person.name ?: "Person",
                repositoryParams = createRepositoryParams(person)
            ))
        }
    }

    fun onPersonItemLongClicked(item: PersonListItem): Boolean {
        eventsSubject.onNext(Event.ShowRenameDialog(item.person))
        return true
    }

    fun onRenamePerson(person: Person, newName: String) {
        if (!Person.isSubjectUid(person.id)) {
            // Only named subjects can be renamed
            eventsSubject.onNext(Event.ShowRenameFailed)
            return
        }

        disposables.add(
            photoPrismSubjectsService.updateSubject(
                uid = person.id,
                body = PhotoPrismSubjectUpdate(
                    name = newName.takeIf { it.isNotEmpty() },
                    favorite = null,
                    hidden = null,
                )
            )
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { updatedSubject ->
                        log.debug { "onRenamePerson(): renamed_person_to: ${updatedSubject.name}" }
                        peopleRepository.update()
                        eventsSubject.onNext(Event.ShowRenameSuccess(updatedSubject.name ?: newName))
                    },
                    { error ->
                        log.error(error) { "onRenamePerson(): failed_to_rename_person" }
                        eventsSubject.onNext(Event.ShowRenameFailed)
                    }
                )
        )
    }

    fun onToggleFavorite(person: Person, isFavorite: Boolean) {
        if (!Person.isSubjectUid(person.id)) {
            return
        }

        val disposable = if (isFavorite) {
            photoPrismSubjectsService.likeSubject(person.id)
        } else {
            photoPrismSubjectsService.dislikeSubject(person.id)
        }

        disposables.add(
            disposable
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        log.debug { "onToggleFavorite(): toggled_favorite_for_person: ${person.id}" }
                        peopleRepository.update()
                    },
                    { error ->
                        log.error(error) { "onToggleFavorite(): failed_to_toggle_favorite" }
                        eventsSubject.onNext(Event.ShowFloatingLoadingFailedError)
                    }
                )
        )
    }

    private fun createRepositoryParams(person: Person): SimpleGalleryMediaRepository.Params {
        return SimpleGalleryMediaRepository.Params(
            searchConfig = defaultSearchConfig.copy(
                personIds = setOf(person.id),
                includePrivate = true,
            ),
        )
    }

    private fun updateItemsList(people: List<Person>) {
        val query = currentSearchInput
        val filteredPeople = if (query == null) {
            people
        } else {
            people.filter { searchPredicate(it, query) }
        }

        itemsList.value = filteredPeople.map { person ->
            PersonListItem(
                person = person,
                mediaPreviewUrlFactory = mediaPreviewUrlFactory,
            )
        }

        mainError.value = when {
            filteredPeople.isEmpty() -> Error.NothingFound
            else -> null
        }
    }

    override fun onCleared() {
        disposables.dispose()
        super.onCleared()
    }
}