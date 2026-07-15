package ua.com.radiokot.photoprism.features.gallery.search.view

import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.chip.Chip
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.ViewGallerySearchHistoryBinding
import ua.com.radiokot.photoprism.features.gallery.search.data.storage.SearchHistoryRepository
import ua.com.radiokot.photoprism.features.gallery.search.view.model.GallerySearchViewModel
import ua.com.radiokot.photoprism.util.ThrottleOnClickListener

class GallerySearchConfigHistoryView(
    private val view: ViewGallerySearchHistoryBinding,
    private val viewModel: GallerySearchViewModel,
    lifecycleOwner: LifecycleOwner,
) : LifecycleOwner by lifecycleOwner {

    private var isInitialized = false
    fun initOnce() = view.historyChipsLayout.post {
        if (isInitialized) {
            return@post
        }

        subscribeToData()

        isInitialized = true
    }

    private fun subscribeToData() {
        val context = view.historyChipsLayout.context
        val chipSpacing =
            context.resources.getDimensionPixelSize(R.dimen.gallery_search_chip_spacing)
        val chipContext = ContextThemeWrapper(
            context,
            R.style.Widget_Wabi_Chip_Filter
        )
        val chipLayoutParams = FlexboxLayout.LayoutParams(
            FlexboxLayout.LayoutParams.WRAP_CONTENT,
            context.resources.getDimensionPixelSize(R.dimen.gallery_search_chip_height),
        ).apply {
            setMargins(0, 0, chipSpacing, chipSpacing)
        }

        val historyChipClickListener = ThrottleOnClickListener { chip ->
            viewModel.onHistoryItemClicked(chip.tag as SearchHistoryRepository.SearchHistoryEntry)
        }
        val clearHistoryClickListener = ThrottleOnClickListener {
            viewModel.onClearHistoryClicked()
        }

        with(view.historyChipsLayout) {
            viewModel.searchHistory.observe(this@GallerySearchConfigHistoryView) { history ->
                removeAllViews()

                history.forEach { entry ->
                    addView(Chip(chipContext).apply {
                        tag = entry
                        text = entry.label ?: context.getString(R.string.search_history_search)
                        setEnsureMinTouchTargetSize(false)
                        setOnClickListener(historyChipClickListener)
                        isCheckable = false
                        isCloseIconVisible = false
                    }, chipLayoutParams)
                }

                if (history.isNotEmpty()) {
                    addView(Chip(chipContext).apply {
                        text = context.getString(R.string.search_history_clear)
                        setEnsureMinTouchTargetSize(false)
                        setOnClickListener(clearHistoryClickListener)
                        isCheckable = false
                        isCloseIconVisible = false
                    }, chipLayoutParams)
                }
            }
        }

        viewModel.isHistorySectionVisible.observe(
            this,
            view.root::isVisible::set
        )
    }
}