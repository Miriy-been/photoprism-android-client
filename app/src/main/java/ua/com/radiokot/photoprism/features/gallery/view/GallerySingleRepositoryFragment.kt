package ua.com.radiokot.photoprism.features.gallery.view

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.forEach
import androidx.core.view.isInvisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.diff.FastAdapterDiffUtil
import com.mikepenz.fastadapter.listeners.addClickListener
import com.mikepenz.fastadapter.listeners.addLongClickListener
import com.mikepenz.fastadapter.scroll.EndlessRecyclerOnScrollListener
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.koin.android.ext.android.inject
import org.koin.android.ext.android.getKoin
import org.koin.android.scope.AndroidScopeComponent
import org.koin.androidx.scope.createFragmentScope
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.ActivityGallerySingleRepositoryBinding
import ua.com.radiokot.photoprism.di.UTC_MONTH_YEAR_DATE_FORMAT
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.capitalized
import ua.com.radiokot.photoprism.extension.ensureItemIsVisible
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.extension.setBetter
import ua.com.radiokot.photoprism.extension.showOverflowItemIcons
import ua.com.radiokot.photoprism.extension.subscribe
import ua.com.radiokot.photoprism.features.albums.data.model.Album
import ua.com.radiokot.photoprism.features.albums.view.AlbumsActivity
import ua.com.radiokot.photoprism.features.albums.view.DestinationAlbumSelectionActivity
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.model.SendableFile
import ua.com.radiokot.photoprism.features.gallery.data.storage.BottomNavItemId
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.logic.FileReturnIntentCreator
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryContentLoadingErrorResources
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryListItem
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryListViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryLoadingFooterListItem
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaDownloadActionsViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GallerySingleRepositoryViewModel
import ua.com.radiokot.photoprism.features.prefs.navcustomize.view.CustomizeNavActivity
import ua.com.radiokot.photoprism.features.viewer.view.MediaViewerActivity
import ua.com.radiokot.photoprism.util.AsyncRecycledViewPoolInitializer
import ua.com.radiokot.photoprism.util.LocalDate
import ua.com.radiokot.photoprism.view.ErrorView
import java.text.DateFormat
import kotlin.math.ceil
import kotlin.math.roundToInt

class GallerySingleRepositoryFragment : Fragment(), AndroidScopeComponent {

    override val scope: Scope by lazy {
        getKoin().getScope(DI_SCOPE_SESSION)
            .apply { linkTo(createFragmentScope()) }
    }

    private val log = kLogger("GallerySingleRepositoryFragment")
    private lateinit var binding: ActivityGallerySingleRepositoryBinding
    private val viewModel: GallerySingleRepositoryViewModel by viewModel()
    private val monthYearDateFormat: DateFormat by inject(named(UTC_MONTH_YEAR_DATE_FORMAT))
    private val galleryItemsAdapter = ItemAdapter<GalleryListItem>()
    private lateinit var endlessScrollListener: EndlessRecyclerOnScrollListener
    private val fileReturnIntentCreator: FileReturnIntentCreator by inject()

    private var title: String? = null
    private var repositoryParams: SimpleGalleryMediaRepository.Params? = null
    private var monthTitle: LocalDate? = null
    private var albumUid: String? = null

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        this::onViewerResult,
    )
    private val storagePermissionRequestLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            onStoragePermissionResult(isGranted)
        }
    private val addDestinationAlbumSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        this::onAddingDestinationAlbumSelectionResult,
    )

    private val downloadProgressView: DownloadProgressView by lazy {
        DownloadProgressView(
            viewModel = viewModel,
            fragmentManager = childFragmentManager,
            errorSnackbarView = binding.galleryRecyclerView,
            lifecycleOwner = viewLifecycleOwner,
        )
    }
    private val dragSelectionView: GalleryDragSelectionView by lazy {
        GalleryDragSelectionView(
            viewModel = viewModel,
            lifecycleOwner = viewLifecycleOwner,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = ActivityGallerySingleRepositoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        title = arguments?.getString(TITLE_ARG)
        @Suppress("DEPRECATION")
        repositoryParams = arguments?.getParcelable(REPO_PARAMS_ARG)
        @Suppress("DEPRECATION")
        monthTitle = arguments?.getSerializable(MONTH_TITLE_ARG) as? LocalDate
        albumUid = arguments?.getString(ALBUM_UID_ARG)

        val repoParams = checkNotNull(repositoryParams) {
            "No repository params specified"
        }

        // Always init as viewing (no file-picker mode inside the gallery)
        viewModel.initViewingOnce(
            repositoryParams = repoParams,
            albumUid = albumUid,
        )

        // Init the list once it is laid out.
        binding.galleryRecyclerView.doOnPreDraw {
            initList(savedInstanceState)
        }
        initToolbar()
        initSwipeRefresh()
        initErrorView()
        initMultipleSelection()
        downloadProgressView.init()

        subscribeToEvents()

        // Allow the view model to intercept back press.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            viewModel.backPressedCallback,
        )
    }

    private fun initToolbar() {
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)
            ?.setSupportActionBar(binding.toolbar)
        val toolbarTitle = title ?: monthTitle?.let {
            monthYearDateFormat.format(it).capitalized()
        }
        if (toolbarTitle != null) {
            binding.toolbar.title = toolbarTitle
        }
    }

    private fun emitSwitchTab(tabId: BottomNavItemId) {
        val parentActivity = requireActivity()
        if (parentActivity is GalleryActivity) {
            parentActivity.onSwitchToTab(tabId)
        } else {
            // Fallback: launch as activity
            when (tabId) {
                BottomNavItemId.PHOTOS -> {
                    startActivity(
                        Intent(requireContext(), GalleryActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    )
                }

                BottomNavItemId.ALBUMS -> {
                    startActivity(
                        Intent(requireContext(), AlbumsActivity::class.java)
                            .setAction(requireActivity().intent.action)
                            .putExtras(requireActivity().intent.extras ?: Bundle())
                            .putExtras(
                                AlbumsActivity.getBundle(
                                    albumType = Album.TypeName.FOLDER,
                                    defaultSearchConfig = SearchConfig.DEFAULT,
                                )
                            )
                    )
                }

                BottomNavItemId.FAVORITES -> {
                    startActivity(
                        Intent(requireContext(), GallerySingleRepositoryActivity::class.java)
                            .putExtras(requireActivity().intent.extras ?: Bundle())
                            .putExtras(
                                GallerySingleRepositoryActivity.getBundle(
                                    title = getString(R.string.favorites),
                                    repositoryParams = SimpleGalleryMediaRepository.Params(
                                        searchConfig = SearchConfig.DEFAULT.copy(
                                            onlyFavorite = true,
                                        ),
                                    ),
                                )
                            )
                    )
                }

                else -> {}
            }
        }
    }

    private fun showMoreSheet() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.more)
            .setItems(
                arrayOf(
                    getString(R.string.sync_settings),
                    getString(R.string.customize_bottom_nav_title),
                    getString(R.string.preferences),
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(
                            requireContext(),
                            ua.com.radiokot.photoprism.features.sync.view.SyncSettingsActivity::class.java
                        )
                    )

                    1 -> startActivity(
                        Intent(requireContext(), CustomizeNavActivity::class.java)
                    )

                    2 -> startActivity(
                        Intent(
                            requireContext(),
                            ua.com.radiokot.photoprism.features.prefs.view.PreferencesActivity::class.java
                        )
                    )
                }
            }
            .show()
    }

    private fun initSwipeRefresh() = with(binding.swipeRefreshLayout) {
        setOnRefreshListener(viewModel::onSwipeRefreshPulled)

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            isRefreshing = isLoading
                    && galleryItemsAdapter.adapterItemCount > 0
                    && !binding.galleryRecyclerView.canScrollVertically(-1)
        }
    }

    private fun initErrorView() {
        binding.errorView.replaces(binding.galleryRecyclerView)
        viewModel.mainError.observe(viewLifecycleOwner) { error ->
            if (error == null) {
                binding.errorView.hide()
                return@observe
            }

            val errorToShow: ErrorView.Error = when (error) {
                GallerySingleRepositoryViewModel.Error.NoMediaFound ->
                    ErrorView.Error.EmptyView(
                        message = error.localizedMessage,
                    )

                else ->
                    ErrorView.Error.General(
                        message = error.localizedMessage,
                        retryButtonText = getString(R.string.try_again),
                        retryButtonClickListener = viewModel::onMainErrorRetryClicked
                    )
            }

            binding.errorView.showError(errorToShow)
        }
    }

    private fun initMultipleSelection() {
        with(binding.selectionBottomAppBar) {
            setNavigationOnClickListener {
                viewModel.onClearSelectionClicked()
            }

            menu.showOverflowItemIcons(isBottomBar = true)

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.share ->
                        viewModel.onShareMultipleSelectionClicked()

                    R.id.download ->
                        viewModel.onDownloadMultipleSelectionClicked()

                    R.id.add_to_album ->
                        viewModel.onAddToAlbumMultipleSelectionClicked()

                    R.id.remove_from_album ->
                        viewModel.onRemoveFromAlbumMultipleSelectionClicked()

                    R.id.archive ->
                        viewModel.onArchiveMultipleSelectionClicked()

                    R.id.delete ->
                        viewModel.onDeleteMultipleSelectionClicked()

                    R.id.add_to_favorites ->
                        viewModel.onAddToFavoritesMultipleSelectionClicked()

                    R.id.remove_from_favorites ->
                        viewModel.onRemoveFromFavoritesMultipleSelectionClicked()
                }

                true
            }

            viewModel.state.subscribeBy { state ->
                isInvisible =
                    state is GallerySingleRepositoryViewModel.State.Viewing

                navigationIcon =
                    if (state is GallerySingleRepositoryViewModel.State.Selecting && state.allowMultiple)
                        ContextCompat.getDrawable(
                            requireContext(),
                            R.drawable.ic_close
                        )
                    else
                        null

                updateMultipleSelectionMenuVisibility()
            }
        }

        binding.doneSelectingFab.setOnClickListener {
            viewModel.onDoneMultipleSelectionClicked()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            ShareSheetShareEventReceiver.shareEvents.subscribe(viewLifecycleOwner) {
                viewModel.onDownloadedMediaFilesShared()
            }
        }

        viewModel.selectedItemsCount.observeOnMain().subscribe(viewLifecycleOwner) { count ->
            binding.selectionBottomAppBarTitleTextView.text =
                if (count == 0)
                    getString(R.string.select_content)
                else
                    count.toString()

            updateMultipleSelectionMenuVisibility()
        }

        viewModel.state.subscribeBy { state ->
            if (state is GallerySingleRepositoryViewModel.State.Selecting.ForOtherApp) {
                viewModel.selectedItemsCount.observeOnMain().subscribe(viewLifecycleOwner) { count ->
                    if (count > 0) {
                        binding.doneSelectingFab.show()
                    } else {
                        binding.doneSelectingFab.hide()
                    }
                }
            } else {
                binding.doneSelectingFab.hide()
            }
        }.autoDispose(this)
    }

    private fun updateMultipleSelectionMenuVisibility() {
        val multipleSelectionItemsCount = viewModel.selectedItemsCount.value ?: 0
        val state = viewModel.currentState
        val areUserSelectionItemsVisible =
            multipleSelectionItemsCount > 0 && state is GallerySingleRepositoryViewModel.State.Selecting.ForUser

        with(binding.selectionBottomAppBar.menu) {
            forEach { menuItem ->
                menuItem.isVisible = areUserSelectionItemsVisible
            }
            findItem(R.id.add_to_favorites).isVisible =
                areUserSelectionItemsVisible && viewModel.canAddSelectedToFavorites
            findItem(R.id.remove_from_favorites).isVisible =
                areUserSelectionItemsVisible && viewModel.canRemoveSelectedFromFavorites
            findItem(R.id.remove_from_album).isVisible =
                areUserSelectionItemsVisible && state.canRemoveFromAlbum
        }
    }

    private fun initList(savedInstanceState: Bundle?) {
        val galleryProgressFooterAdapter = ItemAdapter<GalleryLoadingFooterListItem>().apply {
            setNewList(
                listOf(
                    GalleryLoadingFooterListItem(
                        isLoading = false,
                        canLoadMore = false,
                    )
                )
            )

            viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
                this[0] = GalleryLoadingFooterListItem(
                    isLoading = isLoading,
                    canLoadMore = viewModel.canLoadMore,
                )
            }
        }

        val galleryAdapter = FastAdapter.with(
            listOf(
                galleryItemsAdapter,
                galleryProgressFooterAdapter
            )
        ).apply {
            stateRestorationPolicy = Adapter.StateRestorationPolicy.PREVENT

            addClickListener(
                resolveView = { null },
                resolveViews = { viewHolder: ViewHolder ->
                    when (viewHolder) {
                        is GalleryLoadingFooterListItem.ViewHolder ->
                            listOf(viewHolder.view.loadMoreButton)

                        is GalleryListItem.Media.ViewHolder ->
                            listOf(viewHolder.itemView, viewHolder.view.viewButton)

                        else ->
                            listOf(viewHolder.itemView)
                    }
                },
                onClick = { view, _, _, item ->
                    when (item) {
                        is GalleryListItem.Media ->
                            when (view.id) {
                                R.id.view_button ->
                                    viewModel.onGalleryMediaItemViewClicked(item)

                                else ->
                                    viewModel.onGalleryMediaItemClicked(item)
                            }

                        is GalleryLoadingFooterListItem ->
                            viewModel.onLoadingFooterLoadMoreClicked()
                    }
                }
            )

            addLongClickListener(
                resolveView = { viewHolder: ViewHolder ->
                    (viewHolder as? GalleryListItem.Media.ViewHolder)
                        ?.itemView
                },
                onLongClick = { _, position, _, item ->
                    if (item !is GalleryListItem.Media) {
                        return@addLongClickListener false
                    }

                    viewModel.onGalleryMediaItemLongClicked(
                        item = item,
                        globalPosition = position,
                    )

                    true
                }
            )
        }

        val itemScale = viewModel.itemScale.value!!
        val minItemWidthPx =
            resources.getDimensionPixelSize(R.dimen.list_item_gallery_media_min_size)
        val scaledMinItemWidthPx = ceil(minItemWidthPx * itemScale.factor)
            .toInt()
            .coerceAtLeast(1)

        with(binding.galleryRecyclerView) {
            val listWidth = measuredWidth
                .takeIf { it > 0 }
                ?: FALLBACK_LIST_SIZE
                    .also {
                        log.warn { "initList(): used_fallback_width" }
                    }
            val listHeight = measuredHeight
                .takeIf { it > 0 }
                ?: FALLBACK_LIST_SIZE
                    .also {
                        log.warn { "initList(): used_fallback_height" }
                    }

            val spanCount = (listWidth / scaledMinItemWidthPx).coerceAtLeast(1)
            val cellSize = listWidth / spanCount.toFloat()
            val maxVisibleRowCount = (listHeight / cellSize).roundToInt()
            val maxRecycledMediaViewCount = maxVisibleRowCount * spanCount * 2

            log.debug {
                "initList(): calculated_grid:" +
                        "\nspanCount=$spanCount," +
                        "\nrowWidth=$listWidth," +
                        "\nitemScale=$itemScale," +
                        "\nminItemWidthPx=$minItemWidthPx," +
                        "\nscaledMinItemWidthPx=$scaledMinItemWidthPx," +
                        "\nmaxVisibleRowCount=$maxVisibleRowCount," +
                        "\nmaxRecycledMediaViewCount=$maxRecycledMediaViewCount"
            }

            val gridLayoutManager = GridLayoutManager(context, spanCount).apply {
                spanSizeLookup = object : SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        when (galleryAdapter.getItemViewType(position)) {
                            R.id.list_item_gallery_loading_footer,
                            R.layout.list_item_gallery_small_header,
                            R.layout.list_item_gallery_large_header,
                            ->
                                spanCount

                            else ->
                                1
                        }
                }
            }

            adapter = galleryAdapter
            layoutManager = gridLayoutManager

            endlessScrollListener = object : EndlessRecyclerOnScrollListener(
                footerAdapter = galleryProgressFooterAdapter,
                layoutManager = gridLayoutManager,
                visibleThreshold = gridLayoutManager.spanCount * 5
            ) {
                init {
                    viewModel.isLoading.observe(this@GallerySingleRepositoryFragment.viewLifecycleOwner) { isLoading ->
                        if (isLoading) {
                            disable()
                        } else {
                            enable()
                        }
                    }
                }

                override fun onLoadMore(currentPage: Int) {
                    if (currentPage == 0) {
                        return
                    }

                    log.debug {
                        "onLoadMore(): load_more:" +
                                "\npage=$currentPage"
                    }
                    viewModel.loadMore()
                }
            }
            addOnScrollListener(endlessScrollListener)

            recycledViewPool.setMaxRecycledViews(
                R.id.list_item_gallery_media,
                maxRecycledMediaViewCount
            )
            if (savedInstanceState == null) {
                AsyncRecycledViewPoolInitializer(
                    fastAdapter = galleryAdapter,
                    itemViewType = R.id.list_item_gallery_media,
                    itemViewFactory = GalleryListItem.Media.itemViewFactory,
                    itemViewHolderFactory = GalleryListItem.Media.itemViewHolderFactory,
                )
                    .initPool(
                        recyclerView = binding.galleryRecyclerView,
                        recycledViewsCount = maxRecycledMediaViewCount,
                    )
            }

            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

            dragSelectionView.init(
                globalListAdapter = galleryAdapter,
                recyclerView = this,
                dragToSelectListener = { isActive ->
                    binding.swipeRefreshLayout.isEnabled = !isActive
                },
            )
        }

        val diffCallback = GalleryListItemDiffCallback()
        viewModel
            .itemList
            .observeOnMain()
            .subscribe(viewLifecycleOwner) { newItems ->

                if (newItems.isNotEmpty()) {
                    galleryAdapter.stateRestorationPolicy = Adapter.StateRestorationPolicy.ALLOW
                }

                FastAdapterDiffUtil.setBetter(
                    recyclerView = binding.galleryRecyclerView,
                    adapter = galleryItemsAdapter,
                    items = newItems,
                    callback = diffCallback,
                    detectMoves = false,
                )
            }
    }

    private fun subscribeToEvents() {
        viewModel.itemListEvents.observeOnMain().subscribe(viewLifecycleOwner) { event ->
            log.debug {
                "subscribeToEvents(): received_item_list_event:" +
                        "\nevent=$event"
            }

            when (event) {
                is GalleryListViewModel.Event.OpenViewer -> {
                    // Replaced with the extended event from VM.
                }

                is GalleryListViewModel.Event.EnsureListItemVisible ->
                    binding.galleryRecyclerView.post {
                        binding.galleryRecyclerView.ensureItemIsVisible(
                            itemGlobalPosition = galleryItemsAdapter.getGlobalPosition(event.listItemIndex)
                        )
                    }

                is GalleryListViewModel.Event.ActivateDragSelection -> {
                    // Handled by GalleryDragSelectionView.
                }
            }

            log.debug {
                "subscribeToEvents(): handled_item_list_event:" +
                        "\nevent=$event"
            }
        }

        viewModel.galleryMediaDownloadActionsEvents.observeOnMain().subscribe(viewLifecycleOwner) { event ->
            log.debug {
                "subscribeToEvents(): received_media_files_actions_event:" +
                        "\nevent=$event"
            }

            when (event) {
                is GalleryMediaDownloadActionsViewModel.Event.OpenDownloadedFile ->
                    error("Unsupported event")

                GalleryMediaDownloadActionsViewModel.Event.RequestStoragePermission ->
                    requestStoragePermission()

                is GalleryMediaDownloadActionsViewModel.Event.ReturnDownloadedFiles ->
                    returnDownloadedFiles(event.files)

                is GalleryMediaDownloadActionsViewModel.Event.ShareDownloadedFiles ->
                    shareDownloadedFiles(event.files)

                GalleryMediaDownloadActionsViewModel.Event.ShowFilesDownloadedMessage ->
                    showFloatingMessage(getString(R.string.files_saved_to_downloads))

                GalleryMediaDownloadActionsViewModel.Event.ShowMissingStoragePermissionMessage ->
                    showFloatingMessage(getString(R.string.error_storage_permission_is_required))
            }

            log.debug {
                "subscribeToEvents(): handled_media_files_actions_event:" +
                        "\nevent=$event"
            }
        }

        viewModel.galleryMediaRemoteActionsEvents.observeOnMain().subscribe(viewLifecycleOwner) { event ->
            log.debug {
                "subscribeToEvents(): received_gallery_media_remote_actions_event:" +
                        "\nevent=$event"
            }

            when (event) {
                GalleryMediaRemoteActionsViewModel.Event.OpenAlbumForAddingSelection ->
                    openAddingDestinationAlbumSelection()

                GalleryMediaRemoteActionsViewModel.Event.OpenDeletingConfirmationDialog ->
                    openDeletingConfirmationDialog()

                is GalleryMediaRemoteActionsViewModel.Event.ShowFloatingAddedToAlbumMessage ->
                    showFloatingMessage(
                        getString(
                            R.string.template_selected_added_to_album,
                            event.albumTitle,
                        )
                    )
            }

            log.debug {
                "subscribeToEvents(): handled_gallery_media_remote_actions_event:" +
                        "\nevent=$event"
            }
        }

        viewModel.events.subscribe(viewLifecycleOwner) { event ->
            log.debug {
                "subscribeToEvents(): received_new_event:" +
                        "\nevent=$event"
            }

            when (event) {
                is GallerySingleRepositoryViewModel.Event.ResetScroll -> {
                    resetScroll()
                }

                is GallerySingleRepositoryViewModel.Event.ShowFloatingError ->
                    showFloatingError(event.error)

                is GallerySingleRepositoryViewModel.Event.OpenViewer ->
                    openViewer(
                        mediaIndex = event.mediaIndex,
                        repositoryParams = event.repositoryParams,
                        areActionsEnabled = event.areActionsEnabled,
                        albumUid = event.albumUid,
                    )
            }

            log.debug {
                "subscribeToEvents(): handled_new_event:" +
                        "\nevent=$event"
            }
        }
    }

    private fun resetScroll() {
        log.debug {
            "resetScroll(): resetting_scroll"
        }

        with(binding.galleryRecyclerView) {
            scrollToPosition(0)
            endlessScrollListener.resetPageCount(0)
        }
    }

    private fun showFloatingError(error: GallerySingleRepositoryViewModel.Error) {
        Snackbar.make(binding.galleryRecyclerView, error.localizedMessage, Snackbar.LENGTH_SHORT)
            .setAction(R.string.try_again) { viewModel.onFloatingErrorRetryClicked() }
            .show()
    }

    private fun showFloatingMessage(message: String) {
        Snackbar.make(binding.galleryRecyclerView, message, Snackbar.LENGTH_SHORT)
            .show()
    }

    private fun openViewer(
        mediaIndex: Int,
        repositoryParams: SimpleGalleryMediaRepository.Params,
        areActionsEnabled: Boolean,
        albumUid: String?,
    ) {
        viewerLauncher.launch(
            Intent(requireContext(), MediaViewerActivity::class.java)
                .putExtras(
                    MediaViewerActivity.getBundle(
                        mediaIndex = mediaIndex,
                        repositoryParams = repositoryParams,
                        areActionsEnabled = areActionsEnabled,
                        albumUid = albumUid,
                    )
                )
        )
    }

    private fun onViewerResult(result: ActivityResult) {
        val lastViewedMediaIndex = MediaViewerActivity.getResult(result)
            ?: return

        viewModel.onViewerReturnedLastViewedMediaIndex(lastViewedMediaIndex)
    }

    private fun openAddingDestinationAlbumSelection() {
        addDestinationAlbumSelectionLauncher.launch(
            Intent(requireContext(), DestinationAlbumSelectionActivity::class.java)
                .putExtras(
                    DestinationAlbumSelectionActivity.getBundle(
                        selectedAlbums = emptySet(),
                        isSingleSelection = true,
                    )
                )
        )
    }

    private fun onAddingDestinationAlbumSelectionResult(result: ActivityResult) {
        val bundle = result.data?.extras
        if (result.resultCode == android.app.Activity.RESULT_OK && bundle != null) {
            viewModel.onAlbumForAddingGalleryMediaSelected(
                selectedAlbum = DestinationAlbumSelectionActivity
                    .getSelectedAlbums(bundle)
                    .first()
            )
        }
    }

    private fun openDeletingConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.gallery_deleting_confirmation)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.onDeletingGalleryMediaConfirmed()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun returnDownloadedFiles(
        filesToReturn: List<SendableFile>,
    ) {
        val resultIntent = fileReturnIntentCreator.createIntent(filesToReturn)
        requireActivity().setResult(android.app.Activity.RESULT_OK, resultIntent)

        log.debug {
            "returnDownloadedFiles(): result_set_finishing:" +
                    "\nintent=$resultIntent," +
                    "\nfilesToReturnCount=${filesToReturn.size}"
        }

        requireActivity().finish()
    }

    private fun shareDownloadedFiles(
        files: List<SendableFile>,
    ) {
        val resultIntent = fileReturnIntentCreator.createIntent(files)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val callbackPendingIntent = ShareSheetShareEventReceiver.getPendingIntent(requireContext())

            log.debug {
                "shareDownloadedFiles(): starting_intent_with_callback:" +
                        "\nintent=$resultIntent," +
                        "\ncallbackPendingIntent=$callbackPendingIntent," +
                        "\nfilesCount=${files.size}"
            }

            startActivity(
                Intent.createChooser(
                    resultIntent,
                    getString(R.string.share),
                    callbackPendingIntent.intentSender
                )
            )
        } else {
            log.debug {
                "shareDownloadedFiles(): starting_intent:" +
                        "\nintent=$resultIntent," +
                        "\nfilesCount=${files.size}"
            }

            startActivity(
                Intent.createChooser(
                    resultIntent,
                    getString(R.string.share),
                )
            )

            viewModel.onDownloadedMediaFilesShared()
        }
    }

    private fun requestStoragePermission() {
        storagePermissionRequestLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun onStoragePermissionResult(isGranted: Boolean) {
        viewModel.onStoragePermissionResult(isGranted)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.gallery_single_repository, menu)

        menu?.findItem(R.id.sort)?.setOnMenuItemClickListener {
            viewModel.onSortClicked()
            true
        }

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        setHasOptionsMenu(true)
    }

    private val GallerySingleRepositoryViewModel.Error.localizedMessage: String
        get() = when (this) {
            GallerySingleRepositoryViewModel.Error.NoMediaFound ->
                getString(R.string.nothing_found)

            is GallerySingleRepositoryViewModel.Error.ContentLoadingError ->
                GalleryContentLoadingErrorResources.getMessage(
                    error = contentLoadingError,
                    context = requireContext(),
                )
        }

    companion object {
        private const val FALLBACK_LIST_SIZE = 100
        private const val TITLE_ARG = "title"
        private const val MONTH_TITLE_ARG = "month_title"
        private const val ALBUM_UID_ARG = "album_uid"
        private const val REPO_PARAMS_ARG = "repo_params"

        fun newInstance(
            title: String? = null,
            repositoryParams: SimpleGalleryMediaRepository.Params,
            monthTitle: LocalDate? = null,
            albumUid: String? = null,
        ): GallerySingleRepositoryFragment = GallerySingleRepositoryFragment().apply {
            arguments = Bundle().apply {
                putString(TITLE_ARG, title)
                putSerializable(MONTH_TITLE_ARG, monthTitle)
                putString(ALBUM_UID_ARG, albumUid)
                putParcelable(REPO_PARAMS_ARG, repositoryParams)
            }
        }
    }
}
