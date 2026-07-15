package ua.com.radiokot.photoprism.features.gallery.search.view

import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.observe
import com.google.android.material.chip.Chip
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.ViewGalleryActiveFiltersBinding
import ua.com.radiokot.photoprism.features.gallery.search.view.model.GallerySearchViewModel

/**
 * A horizontal chip bar showing active search filters that can be tapped to remove them.
 */
class GallerySearchActiveFiltersView(
    private val view: ViewGalleryActiveFiltersBinding,
    private val viewModel: GallerySearchViewModel,
    lifecycleOwner: LifecycleOwner,
) : LifecycleOwner by lifecycleOwner {

    init {
        val chipContext = ContextThemeWrapper(
            view.root.context,
            R.style.Widget_Wabi_Chip_Filter,
        )

        viewModel.activeFilters.observe(this) { filters ->
            view.activeFiltersChipGroup.removeAllViews()

            if (filters.isEmpty()) {
                view.root.visibility = View.GONE
                return@observe
            }

            for (filter in filters) {
                val chip = Chip(chipContext).apply {
                    text = filter.label
                    isCheckable = false
                    isCloseIconVisible = true
                    setEnsureMinTouchTargetSize(false)
                    setOnCloseIconClickListener {
                        viewModel.onActiveFilterClicked(filter)
                    }
                    setOnClickListener {
                        viewModel.onActiveFilterClicked(filter)
                    }
                }

                view.activeFiltersChipGroup.addView(chip)
            }

            view.root.visibility = View.VISIBLE
        }
    }
}
