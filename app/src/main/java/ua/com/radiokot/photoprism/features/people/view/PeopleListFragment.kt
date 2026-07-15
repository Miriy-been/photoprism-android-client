package ua.com.radiokot.photoprism.features.people.view

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
import ua.com.radiokot.photoprism.databinding.FragmentPeopleListBinding
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.subscribe
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.search.extension.bindToViewModel
import ua.com.radiokot.photoprism.features.gallery.search.extension.fixCloseButtonColor
import ua.com.radiokot.photoprism.features.gallery.search.extension.hideUnderline
import ua.com.radiokot.photoprism.features.gallery.view.GallerySingleRepositoryActivity
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.features.people.view.model.PeopleListViewModel
import ua.com.radiokot.photoprism.features.people.view.model.PersonListItem
import ua.com.radiokot.photoprism.view.ErrorView

class PeopleListFragment : Fragment(), AndroidScopeComponent {

    override val scope: Scope by lazy {
        getKoin().getScope(DI_SCOPE_SESSION)
            .apply { linkTo(createFragmentScope()) }
    }

    private val log = kLogger("PeopleListFragment")
    private lateinit var binding: FragmentPeopleListBinding
    private val viewModel: PeopleListViewModel by viewModel()
    private var defaultSearchConfig: SearchConfig = SearchConfig.DEFAULT

    private val personDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentPeopleListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("DEPRECATION")
        defaultSearchConfig = requireArguments().getParcelable(DEFAULT_SEARCH_CONFIG_ARG)!!

        @Suppress("DEPRECATION")
        viewModel.initOnce(defaultSearchConfig = defaultSearchConfig)

        initToolbar()
        binding.peopleRecyclerView.doOnPreDraw {
            initList()
        }
        initErrorView()
        initSwipeRefresh()

        subscribeToEvents()
    }

    private fun initToolbar() {
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(binding.toolbar)
        setHasOptionsMenu(true)
    }

    private fun initList() {
        val peopleAdapter = ItemAdapter<PersonListItem>()

        viewModel.itemsList.observe(viewLifecycleOwner, peopleAdapter::setNewList)

        with(binding.peopleRecyclerView) {
            val listWidth = measuredWidth
                .takeIf { it > 0 }
                ?: FALLBACK_LIST_SIZE
                    .also { log.warn { "initList(): used_fallback_width" } }

            val minItemWidthPx = resources.getDimensionPixelSize(R.dimen.list_item_collection_width)
            val spanCount = (listWidth / minItemWidthPx).coerceAtLeast(1)

            adapter = FastAdapter.with(peopleAdapter).apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

                onClickListener = { _, _, item: PersonListItem, _ ->
                    viewModel.onPersonItemClicked(item)
                    true
                }

                onLongClickListener = { _, _, item: PersonListItem, _ ->
                    viewModel.onPersonItemLongClicked(item)
                }
            }

            layoutManager = object : GridLayoutManager(context, spanCount) {
                val rowSpacing = resources.getDimensionPixelSize(R.dimen.list_item_collection_margin_end)

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
                    androidx.core.content.ContextCompat.getDrawable(context, R.drawable.fast_scroll_track)!!
                )
                .setThumbDrawable(
                    androidx.core.content.ContextCompat.getDrawable(context, R.drawable.fast_scroll_thumb)!!
                )
                .build()
        }
    }

    private fun initErrorView() {
        binding.errorView.replaces(binding.peopleRecyclerView)
        viewModel.mainError.observe(viewLifecycleOwner) { mainError ->
            when (mainError) {
                PeopleListViewModel.Error.LoadingFailed ->
                    binding.errorView.showError(
                        ErrorView.Error.General(
                            context = binding.errorView.context,
                            messageRes = R.string.failed_to_load_people,
                            retryButtonTextRes = R.string.try_again,
                            retryButtonClickListener = viewModel::onRetryClicked
                        )
                    )

                PeopleListViewModel.Error.NothingFound ->
                    binding.errorView.showError(
                        ErrorView.Error.EmptyView(
                            context = binding.errorView.context,
                            messageRes = R.string.no_people_found,
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
        log.debug { "subscribeToEvents(): received_new_event: $event" }

        when (event) {
            PeopleListViewModel.Event.ShowFloatingLoadingFailedError ->
                showFloatingLoadingFailedError()

            is PeopleListViewModel.Event.OpenPersonDetail ->
                openPersonDetail(
                    title = event.title,
                    repositoryParams = event.repositoryParams,
                )

            is PeopleListViewModel.Event.ShowRenameDialog ->
                showRenameDialog(event.person)

            is PeopleListViewModel.Event.ShowRenameSuccess ->
                showRenameSuccess(event.personName)

            PeopleListViewModel.Event.ShowRenameFailed ->
                showRenameFailed()
        }
    }

    private fun showFloatingLoadingFailedError() {
        Snackbar.make(binding.swipeRefreshLayout, R.string.failed_to_load_people, Snackbar.LENGTH_SHORT)
            .setAction(R.string.try_again) { viewModel.onRetryClicked() }
            .show()
    }

    private fun openPersonDetail(
        title: String,
        repositoryParams: SimpleGalleryMediaRepository.Params,
    ) = personDetailLauncher.launch(
        Intent(requireContext(), GallerySingleRepositoryActivity::class.java)
            .setAction(requireActivity().intent.action)
            .putExtras(requireActivity().intent.extras ?: Bundle())
            .putExtras(
                GallerySingleRepositoryActivity.getBundle(
                    title = title,
                    repositoryParams = repositoryParams,
                )
            )
    )

    private fun showRenameDialog(person: Person) {
        val inputEditText = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.enter_person_name)
            setText(person.name ?: "")
            requestFocus()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename_person)
            .setView(inputEditText)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.onRenamePerson(person, inputEditText.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameSuccess(personName: String) {
        Snackbar.make(
            binding.swipeRefreshLayout,
            getString(R.string.person_renamed_to, personName),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun showRenameFailed() {
        Snackbar.make(
            binding.swipeRefreshLayout,
            R.string.failed_to_rename_person,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.people_list, menu)

        with(menu.findItem(R.id.search_view)?.actionView as SearchView) {
            queryHint = getString(R.string.search_people)
            fixCloseButtonColor()
            hideUnderline()
            bindToViewModel(viewModel, requireActivity())
        }

        super.onCreateOptionsMenu(menu, inflater)
    }

    companion object {
        private const val FALLBACK_LIST_SIZE = 100
        private const val DEFAULT_SEARCH_CONFIG_ARG = "default_search_config"

        fun newInstance(defaultSearchConfig: SearchConfig): PeopleListFragment =
            PeopleListFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(DEFAULT_SEARCH_CONFIG_ARG, defaultSearchConfig)
                }
            }
    }
}