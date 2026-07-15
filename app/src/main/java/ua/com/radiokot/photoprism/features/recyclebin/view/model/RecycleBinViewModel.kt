package ua.com.radiokot.photoprism.features.recyclebin.view.model

import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.api.photos.model.PhotoPrismBatchPhotoUids
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.checkNotNull
import ua.com.radiokot.photoprism.extension.filterIsInstance
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.extension.subscribe
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryItemsOrder
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.GalleryPreferences
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.logic.DeleteGalleryMediaUseCase
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryContentLoadingError
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryListViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryListViewModelImpl
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaDownloadActionsViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaDownloadActionsViewModelDelegate
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModelDelegate
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinPreferences
import ua.com.radiokot.photoprism.features.recyclebin.logic.AutoClearRecycleBinUseCase
import ua.com.radiokot.photoprism.features.recyclebin.logic.RestoreGalleryMediaUseCase
import ua.com.radiokot.photoprism.util.BackPressActionsStack

class RecycleBinViewModel(
    private val galleryMediaRepositoryFactory: SimpleGalleryMediaRepository.Factory,
    private val restoreGalleryMediaUseCase: RestoreGalleryMediaUseCase,
    private val deleteGalleryMediaUseCase: DeleteGalleryMediaUseCase,
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
    private val autoClearRecycleBinUseCase: AutoClearRecycleBinUseCase,
    private val recycleBinPreferences: RecycleBinPreferences,
    private val galleryPreferences: GalleryPreferences,
    private val listViewModel: GalleryListViewModelImpl,
    private val galleryMediaDownloadActionsVM: GalleryMediaDownloadActionsViewModelDelegate,
    private val galleryMediaRemoteActionsVM: GalleryMediaRemoteActionsViewModelDelegate,
) : ViewModel(),
    GalleryListViewModel by listViewModel,
    GalleryMediaDownloadActionsViewModel by galleryMediaDownloadActionsVM,
    GalleryMediaRemoteActionsViewModel by galleryMediaRemoteActionsVM {

    private val log = kLogger("RecycleBinVM")
    private var isInitialized = false
    private lateinit var itemsOrder: BehaviorSubject<GalleryItemsOrder>
    private val mediaRepositoryChanges = BehaviorSubject.create<SimpleGalleryMediaRepository>()
    private val currentMediaRepository: SimpleGalleryMediaRepository?
        get() = mediaRepositoryChanges.value
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(false)
    private val eventsSubject = PublishSubject.create<Event>()
    val events: Observable<Event> = eventsSubject.observeOnMain()
    private val stateSubject = BehaviorSubject.create<State>()
    val state: Observable<State> = stateSubject.observeOnMain()
    val currentState: State
        get() = stateSubject.value!!
    val mainError = MutableLiveData<Error?>(null)
    var canLoadMore = true
        private set

    private val backPressActionsStack = BackPressActionsStack()
    val backPressedCallback: OnBackPressedCallback =
        backPressActionsStack.onBackPressedCallback
    private val switchBackToViewingOnBackPress = {
        switchToViewing()
    }

    private val archivedSearchConfig = SearchConfig.DEFAULT.copy(archived = true)

    private val archivedRepositoryParams = SimpleGalleryMediaRepository.Params(
        searchConfig = archivedSearchConfig,
    )

    init {
        listViewModel.addDateHeaders = false
    }

    fun initOnce() {
        if (isInitialized) {
            log.debug {
                "initOnce(): already_initialized"
            }

            return
        }

        mediaRepositoryChanges.onNext(
            galleryMediaRepositoryFactory.get(archivedRepositoryParams)
        )

        stateSubject.onNext(State.Viewing)

        listViewModel.initViewing(
            onSwitchedFromViewingToSelecting = {
                stateSubject.onNext(State.Selecting.ForUser)
                backPressActionsStack.pushUniqueAction(switchBackToViewingOnBackPress)
            },
            onSwitchedFromSelectingToViewing = {
                stateSubject.onNext(State.Viewing)
                backPressActionsStack.removeAction(switchBackToViewingOnBackPress)
            },
            shouldPostItemsNow = { repositoryToPostFrom ->
                repositoryToPostFrom == currentMediaRepository
            },
        )

        initCommon()

        // Run auto-clear on initialization.
        recycleBinPreferences.autoClearEnabled
            .firstElement()
            .flatMap { enabled ->
                if (enabled) {
                    recycleBinPreferences.autoClearDays.firstElement()
                        .map { days -> if (days > 0) days else null }
                } else {
                    Observable.just(null).singleOrError()
                }
            }
            .flatMapCompletable { retentionDays ->
                if (retentionDays != null) {
                    autoClearRecycleBinUseCase(retentionDays)
                } else {
                    Completable.complete()
                }
            }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { log.debug { "initOnce(): auto_clear_completed" } },
                { error ->
                    log.error(error) { "initOnce(): auto_clear_failed" }
                }
            )
            .autoDispose(this)

        // Subscribe to local recycle bin item count.
        recycleBinDao.count()
            .observeOnMain()
            .subscribe { count ->
                log.debug {
                    "initOnce(): local_recycle_bin_count=$count"
                }
            }
            .autoDispose(this)

        isInitialized = true

        log.debug {
            "initOnce(): initialized_viewing"
        }
    }

    private fun initCommon() {
        subscribeToItemsOrder()
        subscribeToRepositoryChanges()

        // Replace list VM viewer opening event with the custom one.
        listViewModel.itemListEvents
            .filterIsInstance<GalleryListViewModel.Event.OpenViewer>()
            .map { originalEvent ->
                Event.OpenViewer(
                    mediaIndex = originalEvent.mediaIndex,
                    repositoryParams = originalEvent.repositoryParams,
                    areActionsEnabled = originalEvent.areActionsEnabled,
                )
            }
            .subscribe(this, eventsSubject::onNext)
    }

    private fun subscribeToItemsOrder() {
        itemsOrder = galleryPreferences
            .getItemsOrderBySearchQuery(archivedRepositoryParams.query)

        itemsOrder
            .subscribe { order ->
                mediaRepositoryChanges.onNext(
                    galleryMediaRepositoryFactory.get(
                        params = archivedRepositoryParams.copy(
                            itemsOrder = order,
                        ),
                    )
                )
            }
            .autoDispose(this)
    }

    private fun subscribeToRepositoryChanges() {
        mediaRepositoryChanges
            .distinctUntilChanged()
            .subscribe {
                subscribeToRepository()
                update()

                eventsSubject.onNext(Event.ResetScroll)
            }
            .autoDispose(this)
    }

    private var repositorySubscriptionDisposable: CompositeDisposable? = null
    private fun subscribeToRepository() {
        repositorySubscriptionDisposable?.dispose()

        val disposable = CompositeDisposable()
        repositorySubscriptionDisposable = disposable

        val currentMediaRepository = this.currentMediaRepository
            ?: return

        log.debug {
            "subscribeToRepository(): subscribing:" +
                    "\nrepository=$currentMediaRepository"
        }

        currentMediaRepository.items
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { items ->
                mainError.value = when {
                    items.isEmpty() && !currentMediaRepository.isNeverUpdated ->
                        Error.NoMediaFound

                    else ->
                        null
                }

                postGalleryItemsAsync(currentMediaRepository)
            }
            .addTo(disposable)

        currentMediaRepository.loading
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { isLoading ->
                canLoadMore = !currentMediaRepository.noMoreItems
                this.isLoading.value = isLoading

                // Dismiss the main error when something is loading.
                if (isLoading) {
                    mainError.value = null
                }
            }
            .addTo(disposable)

        currentMediaRepository.errors
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { error ->
                log.error(error) { "subscribeToRepository(): error_occurred" }

                val contentLoadingError = Error.ContentLoadingError(
                    GalleryContentLoadingError.from(error)
                )

                if (itemList.value.isNullOrEmpty()) {
                    mainError.value = contentLoadingError
                } else {
                    eventsSubject.onNext(Event.ShowFloatingError(contentLoadingError))
                }
            }
            .addTo(disposable)

        disposable.autoDispose(this)
    }

    private fun update(force: Boolean = false) {
        val currentMediaRepository = this.currentMediaRepository
            ?: return

        if (!force) {
            currentMediaRepository.updateIfNotFresh()
        } else {
            currentMediaRepository.update()
            eventsSubject.onNext(Event.ResetScroll)
        }
    }

    fun loadMore() {
        val currentMediaRepository = this.currentMediaRepository
            ?: return

        if (!currentMediaRepository.isLoading) {
            log.debug { "loadMore(): requesting_load_more" }

            currentMediaRepository.loadMore()
        }
    }

    fun onMainErrorRetryClicked() {
        update()
    }

    fun onFloatingErrorRetryClicked() {
        loadMore()
    }

    fun onLoadingFooterLoadMoreClicked() {
        loadMore()
    }

    fun onRestoreMultipleSelectionClicked() {
        check(currentState is State.Selecting.ForUser) {
            "Restore multiple selection button is only clickable when selecting"
        }

        check(selectedMediaByUid.isNotEmpty()) {
            "Restore multiple selection button is only clickable when something is selected"
        }

        val selectedUids = selectedMediaByUid.keys.toList()
        val count = selectedUids.size

        restoreGalleryMediaUseCase(selectedUids)
            .doOnSubscribe {
                switchToViewing()
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    currentMediaRepository?.invalidate()
                    eventsSubject.onNext(Event.ItemsRestored(count))
                    log.debug {
                        "onRestoreMultipleSelectionClicked(): restored:" +
                                "\ncount=$count"
                    }
                },
                { error ->
                    log.error(error) { "onRestoreMultipleSelectionClicked(): failed" }
                    eventsSubject.onNext(
                        Event.ShowError(error.message ?: "Failed to restore items")
                    )
                }
            )
            .autoDispose(this)
    }

    fun onPermanentDeleteMultipleSelectionClicked() {
        check(currentState is State.Selecting.ForUser) {
            "Permanent delete multiple selection button is only clickable when selecting"
        }

        check(selectedMediaByUid.isNotEmpty()) {
            "Permanent delete multiple selection button is only clickable when something is selected"
        }

        val selectedUids = selectedMediaByUid.keys.toList()
        val count = selectedUids.size

        Completable.fromAction {
            photoPrismPhotosService.batchDelete(
                PhotoPrismBatchPhotoUids(selectedUids)
            )
        }
            .subscribeOn(Schedulers.io())
            .doOnSubscribe {
                switchToViewing()
            }
            .doOnComplete {
                recycleBinDao.deleteByUids(selectedUids)
                currentMediaRepository?.removeLocally(selectedUids.toSet())
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    eventsSubject.onNext(Event.ItemsPermanentlyDeleted(count))
                    log.debug {
                        "onPermanentDeleteMultipleSelectionClicked(): permanently_deleted:" +
                                "\ncount=$count"
                    }
                },
                { error ->
                    log.error(error) { "onPermanentDeleteMultipleSelectionClicked(): failed" }
                    eventsSubject.onNext(
                        Event.ShowError(error.message ?: "Failed to permanently delete items")
                    )
                }
            )
            .autoDispose(this)
    }

    fun onEmptyRecycleBinClicked() {
        val allItems = currentMediaRepository?.itemsList ?: run {
            log.debug { "onEmptyRecycleBinClicked(): no_repository" }
            return
        }

        if (allItems.isEmpty()) {
            log.debug { "onEmptyRecycleBinClicked(): already_empty" }
            return
        }

        val allUids = allItems.map { it.uid }
        val count = allUids.size

        Completable.fromAction {
            photoPrismPhotosService.batchDelete(
                PhotoPrismBatchPhotoUids(allUids)
            )
        }
            .subscribeOn(Schedulers.io())
            .doOnComplete {
                recycleBinDao.deleteByUids(allUids)
                currentMediaRepository?.removeLocally(allUids.toSet())
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    eventsSubject.onNext(Event.ItemsPermanentlyDeleted(count))
                    log.debug {
                        "onEmptyRecycleBinClicked(): emptied:" +
                                "\ncount=$count"
                    }
                },
                { error ->
                    log.error(error) { "onEmptyRecycleBinClicked(): failed" }
                    eventsSubject.onNext(
                        Event.ShowError(error.message ?: "Failed to empty recycle bin")
                    )
                }
            )
            .autoDispose(this)
    }

    fun onShareMultipleSelectionClicked() {
        check(currentState is State.Selecting.ForUser) {
            "Share multiple selection button is only clickable when selecting"
        }

        check(selectedMediaByUid.isNotEmpty()) {
            "Share multiple selection button is only clickable when something is selected"
        }

        galleryMediaDownloadActionsVM.downloadAndShareGalleryMedia(
            media = selectedMediaByUid.values,
            onShared = {
                switchToViewing()
            }
        )
    }

    fun onDownloadMultipleSelectionClicked() {
        check(currentState is State.Selecting.ForUser) {
            "Download multiple selection button is only clickable when selecting"
        }

        check(selectedMediaByUid.isNotEmpty()) {
            "Download multiple selection button is only clickable when something is selected"
        }

        galleryMediaDownloadActionsVM.downloadGalleryMediaToExternalStorage(
            media = selectedMediaByUid.values,
            onDownloadFinished = {
                switchToViewing()
            }
        )
    }

    fun onSwipeRefreshPulled() {
        log.debug {
            "onSwipeRefreshPulled(): force_updating"
        }

        update(force = true)
    }

    fun onSortClicked() {
        val currentOrder = itemsOrder.value!!

        val newOrder =
            GalleryItemsOrder.entries[(currentOrder.ordinal + 1) % GalleryItemsOrder.entries.size]

        log.debug {
            "onSortClicked(): changing_order:" +
                    "\nnewOrder=$newOrder"
        }

        itemsOrder.onNext(newOrder)
    }

    private fun switchToViewing() {
        assert(currentState is State.Selecting.ForUser) {
            "Switching to viewing is only possible while selecting"
        }

        listViewModel.switchFromSelectingToViewing()
    }

    sealed interface State {
        /**
         * Viewing the recycle bin content.
         */
        object Viewing : State

        /**
         * Viewing the recycle bin content to select something.
         */
        sealed class Selecting(
            /**
             * Whether selection of multiple items is allowed or not.
             */
            val allowMultiple: Boolean,
        ) : State {

            /**
             * Selecting to share the files with any app of the user's choice.
             */
            object ForUser : Selecting(
                allowMultiple = true,
            )
        }
    }

    sealed interface Event {
        /**
         * Reset the scroll (to the top) and the infinite scrolling.
         */
        object ResetScroll : Event

        /**
         * Show a dismissible floating error.
         *
         * The [onFloatingErrorRetryClicked] method should be called
         * if the error assumes retrying.
         */
        @JvmInline
        value class ShowFloatingError(val error: Error) : Event

        class OpenViewer(
            val mediaIndex: Int,
            val repositoryParams: SimpleGalleryMediaRepository.Params,
            val areActionsEnabled: Boolean,
        ) : Event

        /**
         * Items have been restored from the recycle bin.
         */
        class ItemsRestored(val count: Int) : Event

        /**
         * Items have been permanently deleted from the recycle bin.
         */
        class ItemsPermanentlyDeleted(val count: Int) : Event

        /**
         * Show an error message to the user.
         */
        class ShowError(val message: String) : Event
    }

    sealed interface Error {
        @JvmInline
        value class ContentLoadingError(
            val contentLoadingError: GalleryContentLoadingError,
        ) : Error

        /**
         * Nothing is found for the given search.
         * The recycle bin may be empty as well.
         */
        object NoMediaFound : Error
    }
}