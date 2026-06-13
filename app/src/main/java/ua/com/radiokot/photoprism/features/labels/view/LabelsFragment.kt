package ua.com.radiokot.photoprism.features.labels.view

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import ua.com.radiokot.photoprism.databinding.ActivityLabelsBinding
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.subscribe
import ua.com.radiokot.photoprism.features.albums.data.model.Album
import ua.com.radiokot.photoprism.features.albums.view.AlbumsActivity
import ua.com.radiokot.photoprism.features.gallery.search.extension.bindToViewModel
import ua.com.radiokot.photoprism.features.gallery.search.extension.fixCloseButtonColor
import ua.com.radiokot.photoprism.features.gallery.search.extension.hideUnderline
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.BottomNavItemId
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.view.GalleryActivity
import ua.com.radiokot.photoprism.features.gallery.view.GallerySingleRepositoryActivity
import ua.com.radiokot.photoprism.features.labels.view.model.LabelListItem
import ua.com.radiokot.photoprism.features.labels.view.model.LabelsViewModel
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.view.ErrorView

class LabelsFragment : Fragment(), AndroidScopeComponent {

    override val scope: Scope by lazy {
        getKoin().getScope(DI_SCOPE_SESSION)
            .apply { linkTo(createFragmentScope()) }
    }

    private val log = kLogger("LabelsFragment")
    private lateinit var binding: ActivityLabelsBinding
    private val viewModel: LabelsViewModel by viewModel()
    private var defaultSearchConfig: SearchConfig = SearchConfig.DEFAULT

    private val labelLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = ActivityLabelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("DEPRECATION")
        defaultSearchConfig = requireArguments().getParcelable(DEFAULT_SEARCH_CONFIG_ARG)!!

        @Suppress("DEPRECATION")
        viewModel.initOnce(
            defaultSearchConfig = defaultSearchConfig,
        )

        initToolbar()
        // Init the list once it is laid out.
        binding.labelsRecyclerView.doOnPreDraw {
            initList()
        }
        initErrorView()
        initSwipeRefresh()

        subscribeToEvents()

        // Allow the view model to intercept back press.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            viewModel.backPressedCallback,
        )
    }

    private fun initToolbar() {
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(binding.toolbar)
        setHasOptionsMenu(true)
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
                        Intent(requireContext(), ua.com.radiokot.photoprism.features.prefs.navcustomize.view.CustomizeNavActivity::class.java)
                    )

                    2 -> startActivity(
                        Intent(requireContext(), ua.com.radiokot.photoprism.features.prefs.view.PreferencesActivity::class.java)
                    )
                }
            }
            .show()
    }

    private fun initList() {
        val labelsAdapter = ItemAdapter<LabelListItem>()

        viewModel.itemsList.observe(viewLifecycleOwner, labelsAdapter::setNewList)

        with(binding.labelsRecyclerView) {
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

            adapter = FastAdapter.with(labelsAdapter).apply {
                stateRestorationPolicy =
                    RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

                onClickListener = { _, _, item: LabelListItem, _ ->
                    viewModel.onLabelItemClicked(item)
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

    private fun initErrorView() {
        binding.errorView.replaces(binding.labelsRecyclerView)
        viewModel.mainError.observe(viewLifecycleOwner) { mainError ->
            when (mainError) {
                LabelsViewModel.Error.LoadingFailed ->
                    binding.errorView.showError(
                        ErrorView.Error.General(
                            context = binding.errorView.context,
                            messageRes = R.string.failed_to_load_labels,
                            retryButtonTextRes = R.string.try_again,
                            retryButtonClickListener = viewModel::onRetryClicked
                        )
                    )

                LabelsViewModel.Error.NothingFound ->
                    binding.errorView.showError(
                        ErrorView.Error.EmptyView(
                            context = binding.errorView.context,
                            messageRes = R.string.no_labels_found,
                        )
                    )

                null ->
                    binding.errorView.hide()
            }
        }
    }

    private fun initSwipeRefresh() = with(binding.swipeRefreshLayout) {
        setOnRefreshListener(viewModel::onSwipeRefreshPulled)
        viewModel.isLoading.observe(viewLifecycleOwner, ::setRefreshing)
    }

    private fun subscribeToEvents() = viewModel.events.subscribe(viewLifecycleOwner) { event ->
        log.debug {
            "subscribeToEvents(): received_new_event:" +
                    "\nevent=$event"
        }

        when (event) {
            LabelsViewModel.Event.ShowFloatingLoadingFailedError ->
                showFloatingLoadingFailedError()

            is LabelsViewModel.Event.Finish ->
                requireActivity().finish()

            is LabelsViewModel.Event.OpenLabel ->
                openLabel(
                    name = event.name,
                    repositoryParams = event.repositoryParams,
                )
        }
    }

    private fun showFloatingLoadingFailedError() {
        Snackbar.make(
            binding.swipeRefreshLayout,
            R.string.failed_to_load_labels,
            Snackbar.LENGTH_SHORT
        )
            .setAction(R.string.try_again) { viewModel.onRetryClicked() }
            .show()
    }

    private fun openLabel(
        name: String,
        repositoryParams: SimpleGalleryMediaRepository.Params,
    ) = labelLauncher.launch(
        Intent(requireContext(), GallerySingleRepositoryActivity::class.java)
            .setAction(requireActivity().intent.action)
            .putExtras(requireActivity().intent.extras ?: Bundle())
            .putExtras(
                GallerySingleRepositoryActivity.getBundle(
                    title = name,
                    repositoryParams = repositoryParams,
                )
            )
    )

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.labels, menu)

        // Set up the search.
        with(menu.findItem(R.id.search_view)?.actionView as SearchView) {
            queryHint = getString(R.string.enter_the_query)
            fixCloseButtonColor(this)
            hideUnderline(this)
            bindToViewModel(this, requireActivity())
        }

        with(menu.findItem(R.id.show_all)) {
            viewModel.isShowingAllLabels.observe(viewLifecycleOwner) { isShowingAllLabels ->
                if (isShowingAllLabels) {
                    setTitle(R.string.show_only_important_labels)
                    setIcon(R.drawable.ic_eye_off)
                } else {
                    setTitle(R.string.show_all_labels)
                    setIcon(R.drawable.ic_eye)
                }
            }

            setOnMenuItemClickListener {
                viewModel.onShowAllClicked()
                true
            }
        }

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        setHasOptionsMenu(true)
    }

    private fun fixCloseButtonColor(searchView: SearchView) {
        searchView.fixCloseButtonColor()
    }

    private fun hideUnderline(searchView: SearchView) {
        searchView.hideUnderline()
    }

    private fun bindToViewModel(searchView: SearchView, activity: android.app.Activity) {
        searchView.bindToViewModel(
            viewModel = viewModel,
            lifecycleOwner = viewLifecycleOwner,
        )
    }

    companion object {
        private const val FALLBACK_LIST_SIZE = 100
        private const val DEFAULT_SEARCH_CONFIG_ARG = "default_search_config"

        fun newInstance(
            defaultSearchConfig: SearchConfig,
        ): LabelsFragment = LabelsFragment().apply {
            arguments = Bundle().apply {
                putParcelable(DEFAULT_SEARCH_CONFIG_ARG, defaultSearchConfig)
            }
        }
    }
}
