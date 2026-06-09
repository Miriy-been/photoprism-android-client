package ua.com.radiokot.photoprism.features.upload.view

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager



import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.koin.androidx.viewmodel.ext.android.viewModel
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivityUploadFilesBinding
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.setThrottleOnClickListener
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.features.albums.view.DestinationAlbumSelectionActivity
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.upload.view.model.UploadFilesViewModel

class UploadFilesActivity : BaseActivity() {
    private val log = kLogger("UploadFilesActivity")

    private lateinit var view: ActivityUploadFilesBinding
    private val viewModel: UploadFilesViewModel by viewModel()
    private val permissionsRequestLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
            this::onPermissionsResult
        )
    private val albumSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        this::onAlbumSelectionResult
    )
    private val imageSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        this::onImageSelectionResult
    )

    private lateinit var imagesAdapter: SelectedImagesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (goToEnvConnectionIfNoSession()) {
            return
        }

        view = ActivityUploadFilesBinding.inflate(layoutInflater)
        setContentView(view.root)

        initToolbar()
        initImagesGrid()
        initButtons()
        subscribeToData()
        subscribeToEvents()

        if (savedInstanceState == null) {
            openImagePicker()
        }
    }

    private fun initToolbar() {
        setSupportActionBar(view.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
        }
        view.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun initImagesGrid() {
        imagesAdapter = SelectedImagesAdapter(
            onRemoveClick = { position -> viewModel.removeImage(position) },
            contentResolver = contentResolver,
        )
        view.imagesGrid.apply {
            adapter = imagesAdapter
            layoutManager = GridLayoutManager(context, 3)
            setHasFixedSize(true)
        }
    }

    private fun initButtons() {
        view.uploadButton.setOnClickListener {
            viewModel.onUploadClicked()
        }

        view.cancelButton.setOnClickListener {
            viewModel.onCancelClicked()
        }

        view.addMoreButton.setOnClickListener {
            openImagePicker()
        }

        view.pickImagesButton.setOnClickListener {
            openImagePicker()
        }

        view.albumsButton.setOnClickListener {
            viewModel.onAlbumsClicked()
        }
    }

    private fun subscribeToData() {
        viewModel.selectedImages.observe(this) { images ->
            imagesAdapter.submitList(images)

            val hasImages = images.isNotEmpty()
            view.emptyState.isVisible = !hasImages
            view.contentScroll.isVisible = hasImages

            if (hasImages) {
                view.selectedCount.text = getString(
                    R.string.upload_selected_count,
                    images.size
                )
            }

            view.uploadButton.isEnabled = hasImages
        }

        viewModel.summary.observe(this, ::showSummary)

        viewModel.isUploadButtonEnabled.observe(this) { enabled ->
            // Only enable if we also have images
            val hasImages = (viewModel.selectedImages.value?.size ?: 0) > 0
            view.uploadButton.isEnabled = enabled && hasImages
        }
    }

    private fun subscribeToEvents() = viewModel.events.subscribeBy { event ->
        log.debug { "subscribeToEvents(): received_new_event:$event" }

        when (event) {
            UploadFilesViewModel.Event.Finish -> finish()

            UploadFilesViewModel.Event.ShowStartedInBackgroundMessage ->
                Toast.makeText(this, R.string.upload_started_message, Toast.LENGTH_SHORT)
                    .show()

            is UploadFilesViewModel.Event.RequestPermissions ->
                permissionsRequestLauncher.launch(event.permissions)

            is UploadFilesViewModel.Event.OpenAlbumSelectionForResult ->
                albumSelectionLauncher.launch(
                    Intent(this, DestinationAlbumSelectionActivity::class.java)
                        .putExtras(
                            DestinationAlbumSelectionActivity.getBundle(
                                selectedAlbums = event.currentlySelectedAlbums,
                            )
                        )
                )
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        imageSelectionLauncher.launch(
            Intent.createChooser(intent, getString(R.string.select_images))
        )
    }

    private fun onImageSelectionResult(result: ActivityResult) {
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()

            // 单张图片
            data.data?.let { uris.add(it) }

            // 多张图片
            data.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            }

            if (uris.isNotEmpty()) {
                viewModel.initWithImages(uris, contentResolver)
            } else {
                // User cancelled or didn't select anything
                if ((viewModel.selectedImages.value?.size ?: 0) == 0) {
                    // No images yet, stay on empty state
                }
            }
        }
    }

    private fun showSummary(summary: UploadFilesViewModel.Summary) {
        view.summaryItemsLayout.removeAllViews()

        addSummaryItem(
            label = getString(R.string.upload_total_size),
            value = getString(
                R.string.template_import_files_size,
                resources.getQuantityString(
                    R.plurals.files,
                    summary.fileCount,
                    summary.fileCount
                ),
                summary.sizeMb,
            ),
        )
        addSummaryItem(
            label = getString(R.string.upload_server),
            value = summary.libraryRootUrl,
        )
        addSummaryItem(
            label = getString(R.string.albums),
            value = summary.albums
                .takeIf(Collection<*>::isNotEmpty)
                ?.joinToString()
                ?: getString(R.string.import_albums_not_selected),
            onClick = viewModel::onAlbumsClicked,
        )
    }

    private fun addSummaryItem(
        label: String,
        value: String,
        onClick: (() -> Unit)? = null,
    ) {
        val inflater = LayoutInflater.from(this)
        val itemView = inflater.inflate(
            R.layout.item_upload_summary,
            view.summaryItemsLayout,
            false
        )
        itemView.findViewById<TextView>(R.id.label_text).text = label
        itemView.findViewById<TextView>(R.id.value_text).text = value

        if (onClick != null) {
            itemView.setOnClickListener { onClick() }
        }

        view.summaryItemsLayout.addView(itemView)
    }

    private fun onPermissionsResult(results: Map<String, Boolean>) =
        viewModel.onPermissionsResult(results)

    private fun onAlbumSelectionResult(result: ActivityResult) {
        val bundle = result.data?.extras
        if (result.resultCode == RESULT_OK && bundle != null) {
            viewModel.onAlbumSelectionResult(
                selectedAlbums = DestinationAlbumSelectionActivity.getSelectedAlbums(bundle)
            )
        }
    }

    private class SelectedImagesAdapter(
        private val onRemoveClick: (Int) -> Unit,
        private val contentResolver: ContentResolver,
    ) : RecyclerView.Adapter<SelectedImagesAdapter.ViewHolder>() {
        private var items = emptyList<ImportableFile>()

        fun submitList(newItems: List<ImportableFile>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_selected_image,
                parent,
                false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ImageView = itemView.findViewById(R.id.image_view)
            private val filenameText: TextView = itemView.findViewById(R.id.filename_text)
            private val removeButton: View = itemView.findViewById(R.id.remove_button)

            fun bind(item: ImportableFile, position: Int) {
                com.squareup.picasso.Picasso.get()
                    .load(item.contentUri)
                    .centerCrop()
                    .fit()
                    .into(imageView)

                filenameText.text = item.displayName

                removeButton.setOnClickListener {
                    onRemoveClick(position)
                }
            }
        }
    }
}
