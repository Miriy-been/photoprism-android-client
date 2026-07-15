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
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncPreferencesOnPrefs
import ua.com.radiokot.photoprism.features.sync.view.model.FolderItem
import ua.com.radiokot.photoprism.features.sync.view.model.RemoveFolderEvent
import ua.com.radiokot.photoprism.features.sync.view.model.SyncHistoryDisplay
import ua.com.radiokot.photoprism.features.sync.view.model.SyncSettingsViewModel

class SyncSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySyncSettingsBinding
    private val viewModel: SyncSettingsViewModel by viewModel()
    private val disposables = CompositeDisposable()
    private var pendingOpenFolderPicker = false
    /**
     * Tracks whether "Sync now" was clicked before permissions were granted,
     * so we can auto-trigger sync after the user grants permissions.
     */
    private var pendingSyncNow = false

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
                // Auto-add the default camera folder if no folders configured
                viewModel.autoAddDefaultFolderIfNeeded()
                // Trigger pending sync if user clicked "Sync now" before granting permissions
                if (pendingSyncNow) {
                    pendingSyncNow = false
                    viewModel.onSyncNowClicked()
                }
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

    override fun onResume() {
        super.onResume()
        // Issue 1: Refresh pending count when returning to the page
        viewModel.loadData()
        viewModel.startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopAutoRefresh()
    }

    private fun initViews() {
        binding.btnAddFolder.setOnClickListener {
            openFolderPicker()
        }

        binding.btnSyncNow.setOnClickListener {
            if (!checkPermissionsMedia()) {
                pendingSyncNow = true
                permissionsRequestLauncher.launch(permissionsToRequest())
                return@setOnClickListener
            }
            viewModel.onSyncNowClicked()
        }

        // Long press on "Sync now" to trigger full re-sync
        binding.btnSyncNow.setOnLongClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_resync_all_title)
                .setMessage(R.string.sync_resync_all_message)
                .setPositiveButton(R.string.sync_resync_all_confirm) { _, _ ->
                    viewModel.resyncAll()
                    viewModel.onSyncNowClicked()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        // Navigate to full sync history page
        binding.layoutHistory.setOnClickListener {
            startActivity(Intent(this, SyncHistoryActivity::class.java))
        }

        binding.switchWifiOnly.isChecked = viewModel.wifiOnly.value ?: true
        binding.switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleWifiOnly(isChecked)
        }

        // Sync interval selector
        setupSyncIntervalSelector()
    }

    private fun setupSyncIntervalSelector() {
        val currentInterval = viewModel.syncIntervalMin.value ?: SyncPreferencesOnPrefs.INTERVAL_2_HOURS
        val labels = SyncPreferencesOnPrefs.INTERVAL_OPTIONS.map {
            getString(SyncPreferencesOnPrefs.intervalLabelRes(it))
        }
        val currentIndex = SyncPreferencesOnPrefs.INTERVAL_OPTIONS.indexOf(currentInterval).coerceAtLeast(0)

        binding.tvSyncIntervalValue.text = labels[currentIndex]
        binding.layoutSyncInterval.setOnClickListener {
            // Show a simple dialog to pick interval
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_interval_title)
                .setSingleChoiceItems(
                    labels.toTypedArray(),
                    currentIndex,
                ) { dialog, which ->
                    val selected = SyncPreferencesOnPrefs.INTERVAL_OPTIONS[which]
                    binding.tvSyncIntervalValue.text = labels[which]
                    viewModel.setSyncInterval(selected)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateSyncButtonState() {
        val syncing = viewModel.isSyncing.value ?: false
        val processing = viewModel.isProcessing.value ?: false
        val (synced, total) = viewModel.syncProgress.value ?: Pair(0, 0)

        if (processing) {
            binding.btnSyncNow.isEnabled = true
            binding.btnSyncNow.text = getString(R.string.sync_processing)
        } else if (syncing && total > 0) {
            binding.btnSyncNow.isEnabled = true
            binding.btnSyncNow.text = getString(R.string.sync_progress_format, synced, total)
        } else if (syncing) {
            binding.btnSyncNow.isEnabled = true
            binding.btnSyncNow.text = getString(R.string.sync_stop)
        } else {
            binding.btnSyncNow.isEnabled = true
            binding.btnSyncNow.text = getString(R.string.sync_now)
        }
    }

    private fun openFolderPicker() {
        if (!checkPermissionsMedia()) {
            pendingOpenFolderPicker = true
            permissionsRequestLauncher.launch(permissionsToMediaOnly())
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

    /**
     * Permissions needed before starting a sync (includes notification on Android 13+).
     */
    private fun permissionsToRequest(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val list = mutableListOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        list.toTypedArray()
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    /**
     * Permissions needed only for scanning MediaStore (no notification).
     */
    private fun permissionsToMediaOnly(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    private fun checkPermissionsMedia(): Boolean {
        val permissions = permissionsToMediaOnly()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
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

            viewModel.isProcessing
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { processing ->
                    updateSyncButtonState()
                },

            viewModel.syncProgress
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { progress ->
                    updateSyncButtonState()
                },

            viewModel.isLoading
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { loading ->
                    binding.progressFolderLoading.visibility =
                        if (loading) View.VISIBLE else View.GONE
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

            viewModel.syncHistory
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { history ->
                    renderSyncHistory(history)
                },

            viewModel.showMeteredDataWarningEvent
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { show ->
                    if (show) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.sync_metered_warning_title)
                            .setMessage(R.string.sync_metered_warning_message)
                            .setCancelable(true)
                            .setPositiveButton(R.string.sync_metered_warning_continue) { _, _ ->
                                viewModel.onMeteredSyncConfirmed()
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ ->
                                viewModel.onMeteredSyncCancelled()
                            }
                            .setOnDismissListener {
                                viewModel.onMeteredSyncCancelled()
                            }
                            .show()
                    }
                },

            // Show a confirmation dialog before removing a folder
            viewModel.showRemoveFolderConfirmationEvent
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { event ->
                    if (event is RemoveFolderEvent.Show) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.sync_remove_folder_title)
                            .setMessage(getString(R.string.sync_remove_folder_message, event.displayName))
                            .setCancelable(true)
                            .setPositiveButton(R.string.sync_remove_folder_confirm) { _, _ ->
                                viewModel.confirmRemoveFolder(event.bucketId)
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ ->
                                viewModel.cancelRemoveFolder()
                            }
                            .setOnDismissListener {
                                viewModel.cancelRemoveFolder()
                            }
                            .show()
                    }
                },
        )
    }

    private fun renderSyncHistory(history: List<SyncHistoryDisplay>) {
        val totalEntries = history.size
        if (totalEntries == 0) {
            binding.tvHistorySummary.text = getString(R.string.sync_history_empty)
        } else {
            val latest = history.first()
            val status = viewModel.formatHistoryStatus(latest.status)
            val folderInfo = if (latest.folderName != null) {
                "${latest.folderName} "
            } else {
                ""
            }
            val syncInfo = if (latest.totalFiles == 0) {
                getString(R.string.sync_history_no_new_files)
            } else {
                "${latest.syncedCount}/${latest.totalFiles}"
            }
            binding.tvHistorySummary.text = "$status $folderInfo$syncInfo"
        }
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
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) {
                    viewModel.removeFolder(folder.bucketId, folder.displayName)
                }
            }

            if (folder.pendingCount > 0) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = resources.getQuantityString(R.plurals.sync_pending_badge, folder.pendingCount, folder.pendingCount)
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
