package ua.com.radiokot.photoprism.features.gallery.search.view

import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import ua.com.radiokot.photoprism.databinding.ItemGallerySearchSuggestionBinding
import ua.com.radiokot.photoprism.databinding.ViewGallerySearchSuggestionsBinding
import ua.com.radiokot.photoprism.features.gallery.search.view.model.GallerySearchViewModel
import ua.com.radiokot.photoprism.features.gallery.search.view.model.GallerySearchViewModel.SearchSuggestion

/**
 * Dropdown list of search suggestions shown when the user types in the search bar.
 */
class GallerySearchSuggestionsView(
    private val view: ViewGallerySearchSuggestionsBinding,
    private val viewModel: GallerySearchViewModel,
    lifecycleOwner: LifecycleOwner,
) : LifecycleOwner by lifecycleOwner {

    fun initOnce() = Unit

    private val suggestionsAdapter = SuggestionsAdapter(
        onItemClick = { suggestion ->
            viewModel.onSuggestionClicked(suggestion)
            view.root.visibility = View.GONE
        }
    )

    init {
        view.suggestionsRecycler.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = suggestionsAdapter
        }

        viewModel.suggestions.observe(this) { suggestions ->
            if (suggestions.isEmpty()) {
                view.root.visibility = View.GONE
            } else {
                suggestionsAdapter.submitList(suggestions)
                view.root.visibility = View.VISIBLE
            }
        }
    }

    private class SuggestionsAdapter(
        private val onItemClick: (SearchSuggestion) -> Unit,
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SuggestionViewHolder>() {

        private val items = mutableListOf<SearchSuggestion>()

        fun submitList(list: List<SearchSuggestion>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): SuggestionViewHolder {
            val binding = ItemGallerySearchSuggestionBinding.inflate(
                android.view.LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SuggestionViewHolder(binding, onItemClick)
        }

        override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private class SuggestionViewHolder(
        private val binding: ItemGallerySearchSuggestionBinding,
        private val onItemClick: (SearchSuggestion) -> Unit,
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        private var suggestion: SearchSuggestion? = null

        init {
            binding.root.setOnClickListener {
                suggestion?.let(onItemClick)
            }
        }

        fun bind(suggestion: SearchSuggestion) {
            this.suggestion = suggestion
            when (suggestion) {
                is SearchSuggestion.Label -> {
                    binding.icon.setImageResource(
                        ua.com.radiokot.photoprism.R.drawable.ic_label
                    )
                    binding.name.text = suggestion.labelName
                }
                is SearchSuggestion.Person -> {
                    binding.icon.setImageResource(
                        ua.com.radiokot.photoprism.R.drawable.ic_account
                    )
                    binding.name.text = suggestion.personName
                }
                is SearchSuggestion.AlbumSuggestion -> {
                    binding.icon.setImageResource(
                        ua.com.radiokot.photoprism.R.drawable.ic_album
                    )
                    binding.name.text = suggestion.albumName
                }
            }
        }
    }
}
