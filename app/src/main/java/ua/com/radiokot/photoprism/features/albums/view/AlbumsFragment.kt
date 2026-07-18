package ua.com.radiokot.photoprism.features.albums.view

import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import org.koin.android.ext.android.getKoin
import org.koin.android.scope.AndroidScopeComponent
import org.koin.androidx.scope.createFragmentScope
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.scope.Scope
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.ActivityGalleryAlbumsBinding
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.subscribe
import ua.com.radiokot.photoprism.features.albums.data.model.Album
import ua.com.radiokot.photoprism.features.albums.view.model.AlbumListItem
import ua.com.radiokot.photoprism.features.albums.view.model.AlbumSort
import ua.com.radiokot.photoprism.features.albums.view.model.AlbumsViewModel
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.BottomNavItemId
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.view.GalleryActivity
import ua.com.radiokot.photoprism.features.gallery.view.GallerySingleRepositoryActivity
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryViewModel
import ua.com.radiokot.photoprism.features.prefs.navcustomize.view.CustomizeNavActivity
import ua.com.radiokot.photoprism.util.LocalDate
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.view.ErrorView

class AlbumsFragment : Fragment(), AndroidScopeComponent {

    override val scope: Scope by lazy {
        getKoin().getScope(DI_SCOPE_SESSION)
            .apply { linkTo(createFragmentScope()) }
    }

    private val log = kLogger("AlbumsFragment")
    private lateinit var binding: ActivityGalleryAlbumsBinding
    private val viewModel: AlbumsViewModel by viewModel()
    private var albumType: Album.TypeName = Album.TypeName.ALBUM
    private var defaultSearchConfig: SearchConfig = SearchConfig.DEFAULT

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // Album opened, do nothing special
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = ActivityGalleryAlbumsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        albumType = requireArguments().getSerializable(ALBUM_TYPE_ARG) as Album.TypeName
        @Suppress("DEPRECATION")
        defaultSearchConfig = requireArguments().getParcelable(DEFAULT_SEARCH_CONFIG_ARG)!!

        @Suppress("DEPRECATION")
        viewModel.initOnce(
            albumType = albumType,
            defaultSearchConfig = defaultSearchConfig,
        )

        initToolbar()
        // Init the list once it is laid out.
        binding.albumsRecyclerView.doOnPreDraw {
            initList()
        }
        initErrorView()
        initSwipeRefresh()

        // 初始化创建相册按钮
        binding.createAlbumButton.visibility =
            if (albumType == Album.TypeName.ALBUM) View.VISIBLE else View.GONE
        binding.createAlbumButton.setOnClickListener {
            showCreateAlbumDialog()
        }

        subscribeToEvents()

        // Allow the view model to intercept back press.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            viewModel.backPressedCallback,
        )
    }

    private fun initToolbar() {
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(binding.toolbar)

        val title = when (albumType) {
            Album.TypeName.FOLDER -> getString(R.string.folders)
            Album.TypeName.MONTH -> getString(R.string.calendar)
            Album.TypeName.ALBUM -> getString(R.string.albums)
        }
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title = title
        binding.toolbar.title = title
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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
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
                        Intent(requireContext(), ua.com.radiokot.photoprism.features.sync.view.SyncSettingsActivity::class.java)
                    )

                    1 -> startActivity(
                        Intent(requireContext(), CustomizeNavActivity::class.java)
                    )

                    2 -> startActivity(
                        Intent(requireContext(), ua.com.radiokot.photoprism.features.prefs.view.PreferencesActivity::class.java)
                    )
                }
            }
            .show()
    }

    private fun initList() {
        val albumsAdapter = ItemAdapter<AlbumListItem>()

        viewModel.itemsList.observe(viewLifecycleOwner, albumsAdapter::setNewList)

        with(binding.albumsRecyclerView) {
            val listWidth = measuredWidth
                .takeIf { it > 0 }
                ?: FALLBACK_LIST_SIZE
                    .also {
                        log.warn { "initList(): used_fallback_width" }
                    }

            val minItemWidthPx =
                resources.getDimensionPixelSize(R.dimen.list_item_collection_width)
            val spanCount = (listWidth / minItemWidthPx).coerceAtLeast(1)

            log.debug {
                "initList(): calculated_grid:" +
                        "\nspanCount=$spanCount," +
                        "\nrowWidth=$listWidth," +
                        "\nminItemWidthPx=$minItemWidthPx"
            }

            adapter = FastAdapter.with(albumsAdapter).apply {
                stateRestorationPolicy =
                    RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

                onClickListener = { _, _, item: AlbumListItem, _ ->
                    viewModel.onAlbumItemClicked(item)
                    true
                }

                onLongClickListener = { _, _, item: AlbumListItem, _ ->
                    onAlbumItemLongClicked(item)
                    true
                }
            }

            layoutManager = object : GridLayoutManager(context, spanCount) {
                val rowSpacing: Int =
                    resources.getDimensionPixelSize(R.dimen.list_item_collection_margin_end)

                override fun generateLayoutParams(
                    c: android.content.Context,
                    attrs: android.util.AttributeSet
                ): RecyclerView.LayoutParams {
                    return super.generateLayoutParams(c, attrs).apply {
                        width = RecyclerView.LayoutParams.MATCH_PARENT
                        bottomMargin = rowSpacing
                    }
                }
            }

            FastScrollerBuilder(this)
                .useMd2Style()
                .setTrackDrawable(
                    androidx.core.content.ContextCompat.getDrawable(
                        context,
                        R.drawable.fast_scroll_track
                    )!!
                )
                .setThumbDrawable(
                    androidx.core.content.ContextCompat.getDrawable(
                        context,
                        R.drawable.fast_scroll_thumb
                    )!!
                )
                .build()
        }
    }

    private fun initSwipeRefresh() = with(binding.swipeRefreshLayout) {
        setOnRefreshListener(viewModel::onSwipeRefreshPulled)
        viewModel.isLoading.observe(viewLifecycleOwner, ::setRefreshing)
    }

    private fun initErrorView() {
        binding.errorView.replaces(binding.albumsRecyclerView)
        viewModel.mainError.observe(viewLifecycleOwner) { mainError ->
            when (mainError) {
                AlbumsViewModel.Error.LoadingFailed ->
                    binding.errorView.showError(
                        ErrorView.Error.General(
                            context = binding.errorView.context,
                            messageRes = when (albumType) {
                                Album.TypeName.FOLDER -> R.string.failed_to_load_folders
                                Album.TypeName.ALBUM -> R.string.failed_to_load_albums
                                Album.TypeName.MONTH -> R.string.failed_to_load_calendar
                            },
                            retryButtonTextRes = R.string.try_again,
                            retryButtonClickListener = viewModel::onRetryClicked
                        )
                    )

                AlbumsViewModel.Error.NothingFound ->
                    binding.errorView.showError(
                        ErrorView.Error.EmptyView(
                            context = binding.errorView.context,
                            messageRes = when (albumType) {
                                Album.TypeName.FOLDER -> R.string.no_folders_found
                                Album.TypeName.ALBUM -> R.string.no_albums_found
                                Album.TypeName.MONTH -> R.string.nothing_found
                            },
                        )
                    )

                null ->
                    binding.errorView.hide()
            }
        }
    }

    private fun subscribeToEvents() = viewModel.events.subscribe(viewLifecycleOwner) { event ->
        log.debug {
            "subscribeToEvents(): received_new_event:" +
                    "\nevent=$event"
        }

        when (event) {
            AlbumsViewModel.Event.ShowFloatingLoadingFailedError ->
                showFloatingLoadingFailedError()

            is AlbumsViewModel.Event.ShowFloatingMessage ->
                Snackbar.make(binding.swipeRefreshLayout, event.message, Snackbar.LENGTH_SHORT).show()

            is AlbumsViewModel.Event.Finish ->
                requireActivity().finish()

            is AlbumsViewModel.Event.OpenAlbum ->
                openAlbum(
                    title = event.title,
                    monthTitle = event.monthTitle,
                    albumUid = event.albumUid,
                    repositoryParams = event.repositoryParams,
                )

            is AlbumsViewModel.Event.OpenSortDialog ->
                openSortDialog(
                    currentSort = event.currentSort,
                )
        }
    }

    private fun showFloatingLoadingFailedError() {
        Snackbar.make(
            binding.swipeRefreshLayout,
            when (albumType) {
                Album.TypeName.FOLDER -> R.string.failed_to_load_folders
                Album.TypeName.ALBUM -> R.string.failed_to_load_albums
                Album.TypeName.MONTH -> R.string.failed_to_load_calendar
            },
            Snackbar.LENGTH_SHORT
        )
            .setAction(R.string.try_again) { viewModel.onRetryClicked() }
            .show()
    }

    private fun openAlbum(
        title: String,
        monthTitle: LocalDate?,
        albumUid: String?,
        repositoryParams: SimpleGalleryMediaRepository.Params,
    ) = folderLauncher.launch(
        Intent(requireContext(), GallerySingleRepositoryActivity::class.java)
            .setAction(requireActivity().intent.action)
            .putExtras(requireActivity().intent.extras ?: Bundle())
            .putExtras(
                GallerySingleRepositoryActivity.getBundle(
                    title = title,
                    monthTitle = monthTitle,
                    albumUid = albumUid,
                    repositoryParams = repositoryParams,
                )
            )
    )

    private fun openSortDialog(currentSort: AlbumSort) {
        val fm = childFragmentManager
        val fragment =
            (fm.findFragmentByTag(AlbumSortDialogFragment.TAG)
                    as? AlbumSortDialogFragment)
                ?: AlbumSortDialogFragment().apply {
                    arguments = AlbumSortDialogFragment.getBundle(
                        sort = currentSort,
                    )
                }

        if (!fragment.isAdded || !fragment.showsDialog) {
            fragment.showNow(fm, AlbumSortDialogFragment.TAG)
        }
    }

    private fun showCreateAlbumDialog() {
        val inputEditText = EditText(requireContext()).apply {
            hint = getString(R.string.enter_album_name)
            requestFocus()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_album)
            .setView(inputEditText)
            .setPositiveButton(R.string.create_album) { _, _ ->
                val albumName = inputEditText.text.toString()
                if (albumName.isNotBlank()) {
                    viewModel.createAlbum(albumName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onAlbumItemLongClicked(item: AlbumListItem): Boolean {
        log.debug {
            "onAlbumItemLongClicked(): item_long_clicked:" +
                    "\nitem=$item"
        }

        val album = item.source ?: return false

        showAlbumActionsBottomSheet(album.uid, album.title)
        return true
    }

    private fun showAlbumActionsBottomSheet(albumUid: String, albumTitle: String) {
        val bottomSheet = AlbumActionsBottomSheet.newInstance()

        bottomSheet.onEditNameClicked = {
            showEditAlbumNameDialog(albumUid, albumTitle)
        }

        bottomSheet.onDownloadZipClicked = {
            // Download ZIP is not available from the album list view.
        }

        bottomSheet.onDeleteClicked = {
            showDeleteAlbumConfirmation(albumUid, albumTitle)
        }

        bottomSheet.showNow(childFragmentManager, AlbumActionsBottomSheet.TAG)
    }

    private fun showDeleteAlbumConfirmation(albumUid: String, albumTitle: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_album_confirmation)
            .setMessage(getString(R.string.delete_album_confirmation_message, albumTitle))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteAlbum(albumUid)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditAlbumNameDialog(albumUid: String, currentName: String) {
        val inputEditText = EditText(requireContext()).apply {
            hint = getString(R.string.enter_album_name)
            setText(currentName)
            requestFocus()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_album)
            .setView(inputEditText)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = inputEditText.text.toString()
                if (newName.isNotBlank()) {
                    viewModel.updateAlbum(albumUid, newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.albums, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Set up the options menu for this fragment
        setHasOptionsMenu(true)
    }

    companion object {
        private const val FALLBACK_LIST_SIZE = 100
        private const val ALBUM_TYPE_ARG = "album_type"
        private const val DEFAULT_SEARCH_CONFIG_ARG = "default_search_config"

        fun newInstance(
            albumType: Album.TypeName,
            defaultSearchConfig: SearchConfig,
        ): AlbumsFragment = AlbumsFragment().apply {
            arguments = Bundle().apply {
                putSerializable(ALBUM_TYPE_ARG, albumType)
                putParcelable(DEFAULT_SEARCH_CONFIG_ARG, defaultSearchConfig)
            }
        }
    }
}
