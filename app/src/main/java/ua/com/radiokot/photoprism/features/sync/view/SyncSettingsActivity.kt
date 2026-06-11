package ua.com.radiokot.photoprism.features.sync.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.koin.androidx.viewmodel.ext.android.viewModel
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivitySyncSettingsBinding
import ua.com.radiokot.photoprism.features.sync.view.model.FolderItem
import ua.com.radiokot.photoprism.features.sync.view.model.SyncSettingsViewModel

class SyncSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySyncSettingsBinding
    private val viewModel: SyncSettingsViewModel by viewModel()
    private val disposables = CompositeDisposable()
    private var pendingOpenFolderPicker = false

    private val permissionsRequestLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                if (pendingOpenFolderPicker) {
                    pendingOpenFolderPicker = false
                    openFolderPickerInternal()
                }
                viewModel.loadData()
            } else {
                pendingOpenFolderPicker = false
                Snackbar.make(
                    binding.root,
                    R.string.sync_permission_denied,
                    Snackbar.LENGTH_INDEFINITE,
                ).setAction(R.string.preferences) {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        startActivity(this)
                    }
                }.show()
            }
        }

    companion object {
        private const val REQUEST_PICK_FOLDER = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (goToEnvConnectionIfNoSession()) {
            return
        }

        binding = ActivitySyncSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initViews()
        checkPermissions()
        subscribeToViewModel()
    }

    private fun initViews() {
        binding.btnAddFolder.setOnClickListener {
            openFolderPicker()
        }

        binding.btnSyncNow.setOnClickListener {
            viewModel.onSyncNowClicked()
        }

        binding.switchWifiOnly.isChecked = viewModel.wifiOnly.value ?: true
        binding.switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleWifiOnly(isChecked)
        }
    }

    private fun updateSyncButtonState() {
        val pending = viewModel.totalPendingCount.value ?: 0
        val syncing = viewModel.isSyncing.value ?: false
        
        if (syncing) {
            binding.btnSyncNow.isEnabled = false
            binding.btnSyncNow.text = getString(R.string.sync_syncing)
        } else {
            binding.btnSyncNow.isEnabled = pending > 0
            binding.btnSyncNow.text = getString(R.string.sync_now)
        }
    }

    private fun openFolderPicker() {
        if (!checkPermissions(false)) {
            pendingOpenFolderPicker = true
            permissionsRequestLauncher.launch(permissionsToRequest())
            return
        }
        openFolderPickerInternal()
    }

    private fun openFolderPickerInternal() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Start from DCIM directory where photos are usually stored
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:DCIM"
                )
            )
        }
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    /**
     * @return true if all required permissions are already granted
     */
    private fun checkPermissions(showDeniedSnackbar: Boolean = true): Boolean {
        val permissions = permissionsToRequest()
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted && showDeniedSnackbar) {
            permissionsRequestLauncher.launch(permissions)
        }
        return allGranted
    }

    private fun permissionsToRequest(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FOLDER || resultCode != RESULT_OK) return

        data?.data?.let { treeUri ->
            // Take persistable permission so we can access later
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val folderName = extractFolderName(treeUri)
            val relativePath = extractRelativePath(treeUri)

            if (folderName != null) {
                // Use the tree URI string as unique bucket ID
                viewModel.addSelectedFolder(
                    bucketId = treeUri.toString(),
                    displayName = folderName,
                    relativePath = relativePath
                )
            }
        }
    }

    /**
     * Extract a human-readable folder name from the tree URI.
     * e.g. "tree/primary:DCIM/Camera" -> "Camera"
     */
    private fun extractFolderName(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        // docId looks like "primary:DCIM/Camera"
        val parts = docId.split(":")
        if (parts.size < 2) return docId
        val path = parts.drop(1).joinToString(":")
        return path.split("/").lastOrNull { it.isNotEmpty() }
    }

    /**
     * Extract relative path from the tree URI.
     * e.g. "tree/primary:DCIM/Camera" -> "DCIM/Camera/"
     */
    private fun extractRelativePath(uri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":")
        if (parts.size < 2) return docId
        val path = parts.drop(1).joinToString(":")
        return if (path.endsWith('/')) path else "$path/"
    }

    private fun subscribeToViewModel() {
        disposables.addAll(
            viewModel.folders
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { folders ->
                    renderFolderTags(folders)
                },

            viewModel.totalSyncedCount
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { synced ->
                    binding.tvSyncedCount.text = synced.toString()
                },

            viewModel.totalPendingCount
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { pending ->
                    binding.tvPendingCount.text = pending.toString()
                    updateSyncButtonState()
                },

            viewModel.isSyncing
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { syncing ->
                    updateSyncButtonState()
                },

            viewModel.lastFullSyncAt
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { timestamp ->
                    binding.tvLastSyncTime.text = viewModel.formatLastSyncTime(timestamp)
                },

            viewModel.errorEvent
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { message ->
                    if (message.isNotEmpty()) {
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                },
        )
    }

    private fun renderFolderTags(enabledFolders: List<FolderItem>) {
        val container = binding.llFolderTags
        // Remove all views except tv_no_folders
        for (i in container.childCount - 1 downTo 1) {
            container.removeViewAt(i)
        }

        if (enabledFolders.isEmpty()) {
            binding.tvNoFolders.visibility = View.VISIBLE
            binding.tvFolderHint.visibility = View.VISIBLE
            return
        }

        binding.tvNoFolders.visibility = View.GONE
        binding.tvFolderHint.visibility = View.GONE

        for (folder in enabledFolders) {
            val chipLayout = LayoutInflater.from(this)
                .inflate(R.layout.list_item_sync_folder, container, false) as ViewGroup

            val tvName = chipLayout.findViewById<TextView>(R.id.tv_folder_name)
            val tvPath = chipLayout.findViewById<TextView>(R.id.tv_folder_path)
            val tvBadge = chipLayout.findViewById<TextView>(R.id.tv_pending_badge)
            val cb = chipLayout.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_enabled)

            tvName.text = folder.displayName
            tvPath.text = folder.relativePath

            cb.isChecked = true
            cb.setOnCheckedChangeListener { _, _ ->
                viewModel.removeFolder(folder.bucketId)
            }

            if (folder.pendingCount > 0) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = "${folder.pendingCount} new"
            } else {
                tvBadge.visibility = View.GONE
            }

            container.addView(chipLayout)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }
}
