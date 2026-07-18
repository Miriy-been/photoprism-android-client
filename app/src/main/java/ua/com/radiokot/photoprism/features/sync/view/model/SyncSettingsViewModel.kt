package ua.com.radiokot.photoprism.features.sync.view.model

import androidx.lifecycle.ViewModel
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.base.util.ConnectivityChecker
import ua.com.radiokot.photoprism.features.sync.data.model.SyncFolder
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncFolderDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncHistoryItemDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncPreferencesOnPrefs
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import ua.com.radiokot.photoprism.features.sync.logic.ScanLocalFoldersUseCase
import ua.com.radiokot.photoprism.features.sync.logic.ScheduleSyncUseCase
import ua.com.radiokot.photoprism.features.sync.logic.SyncWorker
import ua.com.radiokot.photoprism.features.sync.data.model.SyncHistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FolderItem(
    val bucketId: String,
    val displayName: String,
    val relativePath: String,
    val isEnabled: Boolean,
    val totalCount: Int = 0,
    val pendingCount: Int = 0,
)

data class SyncHistoryDisplay(
    val startedAt: String,
    val syncedCount: Int,
    val failedCount: Int,
    val totalFiles: Int,
    val status: String,
    val folderName: String? = null,
)

class SyncSettingsViewModel(
    private val scanLocalFoldersUseCase: ScanLocalFoldersUseCase,
    private val syncedFileDao: SyncedFileDao,
    private val syncFolderDao: SyncFolderDao,
    private val syncHistoryDao: SyncHistoryItemDao,
    private val syncPreferences: SyncPreferencesOnPrefs,
    private val scheduleSyncUseCase: ScheduleSyncUseCase,
    private val workManager: WorkManager,
    private val connectivityChecker: ConnectivityChecker,
) : ViewModel() {

    val folders = BehaviorSubject.createDefault<List<FolderItem>>(emptyList())
    val totalSyncedCount = BehaviorSubject.createDefault(0)
    val totalPendingCount = BehaviorSubject.createDefault(0)
    val lastFullSyncAt = BehaviorSubject.createDefault(0L)
    val wifiOnly = syncPreferences.wifiOnly
    val syncIntervalMin = syncPreferences.syncIntervalMin
    val isLoading = BehaviorSubject.createDefault(false)
    val isSyncing = BehaviorSubject.createDefault(false)
    /** Current sync progress: (syncedCount, totalFiles), (0,0) when not syncing. */
    val syncProgress = BehaviorSubject.createDefault(Pair(0, 0))
    /** True when the server is indexing uploaded files (ProcessingUpload phase). */
    val isProcessing = BehaviorSubject.createDefault(false)
    val errorEvent = BehaviorSubject.create<String>()
    val syncHistory = BehaviorSubject.createDefault<List<SyncHistoryDisplay>>(emptyList())
    /** Emits a non-null value when user needs to confirm syncing on metered data. */
    val showMeteredDataWarningEvent = BehaviorSubject.createDefault(false)

    /**
     * Emits a Show event when user wants to remove a folder.
     * Activity observes this and shows a confirmation dialog.
     */
    val showRemoveFolderConfirmationEvent = BehaviorSubject.createDefault<RemoveFolderEvent>(RemoveFolderEvent.Dismiss)

    private val log = kLogger("SyncSettingsVM")
    private val disposables = CompositeDisposable()
    private var autoRefreshDisposable: Disposable? = null
    private var loadDataDisposable: Disposable? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val syncWorkObserver = androidx.lifecycle.Observer<List<WorkInfo>> { workInfos ->
        val runningWork = workInfos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }

        if (runningWork?.state == WorkInfo.State.RUNNING) {
            val syncedCount = runningWork.progress.getInt("synced_count", 0)
            val totalFiles = runningWork.progress.getInt("total_files", 0)
            val uploadPercent = runningWork.progress.getDouble("upload_percent", -1.0)

            val processing = uploadPercent == -2.0
            isProcessing.onNext(processing)

            if (!processing && totalFiles > 0) {
                syncProgress.onNext(Pair(syncedCount, totalFiles))
            }
            isSyncing.onNext(true)
        } else {
            syncProgress.onNext(Pair(0, 0))
            isSyncing.onNext(false)
            isProcessing.onNext(false)
        }

        if (runningWork == null) {
            loadData()
            loadSyncHistory()
        }
    }

    init {
        loadData()
        loadSyncHistory()
        observeSyncWorkerStatus()
    }

    /**
     * Auto-refresh pending count every 5 seconds while the page is visible.
     */
    fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshDisposable = Observable.interval(5, java.util.concurrent.TimeUnit.SECONDS)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ loadData() }, { log.error(it) { "autoRefresh(): failed" } })
            .also { autoRefreshDisposable = it }
    }

    fun stopAutoRefresh() {
        autoRefreshDisposable?.dispose()
        autoRefreshDisposable = null
    }

    private fun observeSyncWorkerStatus() {
        workManager.getWorkInfosByTagLiveData(SyncWorker.TAG)
            .observeForever(syncWorkObserver)
    }

    fun loadData() {
        loadDataDisposable?.dispose()
        loadDataDisposable = Single.fromCallable {
            // 1. Get all folders from MediaStore
            val allMediaFolders = scanLocalFoldersUseCase.getAllMediaFolders()

            // 2. Get enabled folders from DB
            val enabledDbFolders = runBlocking {
                syncFolderDao.getEnabledFolders()
            }
            // Build a set of matchable bucketIds (strip "auto:" prefix for auto-detected folders)
            val enabledBucketIds = enabledDbFolders.map { it.bucketId }.toSet()
            val matchableEnabledIds = enabledDbFolders
                .map { it.bucketId.removePrefix("auto:") }
                .toSet()

            // 3. Get total counts for all MediaStore folders
            val allBucketIds = allMediaFolders.map { it.first }.toSet()
            val totalCounts = scanLocalFoldersUseCase.countMediaInFolders(allBucketIds)

            // 4. Build FolderItem for all MediaStore folders
            val mediaStoreItems = allMediaFolders.map { (bucketId, displayName, relativePath) ->
                val isEnabled = bucketId in enabledBucketIds || bucketId in matchableEnabledIds
                val totalCount = totalCounts[bucketId] ?: 0
                val pendingCount = if (isEnabled) {
                    runBlocking {
                        scanLocalFoldersUseCase.countNewFilesByPath(relativePath, syncedFileDao)
                    }
                } else {
                    0
                }
                FolderItem(
                    bucketId = bucketId,
                    displayName = displayName,
                    relativePath = relativePath,
                    isEnabled = isEnabled,
                    totalCount = totalCount,
                    pendingCount = pendingCount,
                )
            }.toMutableList()

            // Filter out folders with < 3 files (non-album noise, mainstream gallery behavior)
            mediaStoreItems.removeAll { it.totalCount < 3 }

            // 5. Auto-enable Camera folder on first use (no DB folders configured yet)
            val hasEnabledFolders = enabledBucketIds.isNotEmpty()
            if (!hasEnabledFolders) {
                val defaultCamera = scanLocalFoldersUseCase.findDefaultCameraFolder()
                if (defaultCamera != null) {
                    val (camBucketId, _, _) = defaultCamera
                    // Mark the Camera folder as enabled in the list
                    mediaStoreItems.firstOrNull { it.bucketId == camBucketId }?.let { item ->
                        val idx = mediaStoreItems.indexOf(item)
                        if (idx >= 0) {
                            val pendingCount = runBlocking {
                                scanLocalFoldersUseCase.countNewFilesByPath(item.relativePath, syncedFileDao)
                            }
                            mediaStoreItems[idx] = item.copy(
                                isEnabled = true,
                                pendingCount = pendingCount,
                            )
                        }
                    }
                    // Also upsert into DB so subsequent loads persist the choice
                    runBlocking {
                        val existing = syncFolderDao.getByBucketId(camBucketId)
                        if (existing == null) {
                            syncFolderDao.upsert(
                                SyncFolder(
                                    bucketId = camBucketId,
                                    displayName = defaultCamera.second,
                                    relativePath = defaultCamera.third,
                                    isEnabled = true,
                                    lastSyncAt = null,
                                )
                            )
                            log.info {
                                "loadData(): auto-enabled Camera folder " +
                                        "bucketId=$camBucketId"
                            }
                        }
                    }
                }
            }

            // 6. Handle SAF-selected folders not in MediaStore (e.g. tree URIs)
            val mediaStoreBucketIds = allMediaFolders.map { it.first }.toSet()
            val extraDbFolders = enabledDbFolders.filter { dbFolder ->
                val cleanId = dbFolder.bucketId.removePrefix("auto:")
                cleanId !in mediaStoreBucketIds
            }
            val extraItems = extraDbFolders.map { folder ->
                val pendingCount = runBlocking {
                    scanLocalFoldersUseCase.countNewFilesByPath(folder.relativePath, syncedFileDao)
                }
                FolderItem(
                    bucketId = folder.bucketId,
                    displayName = folder.displayName,
                    relativePath = folder.relativePath,
                    isEnabled = true,
                    totalCount = 0, // can't determine from MediaStore
                    pendingCount = pendingCount,
                )
            }

            // 7. Combine: enabled first (sorted by totalCount desc), then disabled, then extras
            val allItems = (mediaStoreItems + extraItems).sortedWith(
                compareByDescending<FolderItem> { it.isEnabled }
                    .thenByDescending { it.totalCount }
            )

            // 8. Compute summary counts
            val synced = if (enabledBucketIds.isNotEmpty()) {
                runBlocking { syncedFileDao.getCountByBucketIds(enabledBucketIds.toList()) }
            } else {
                0
            }
            val pending = allItems
                .filter { it.isEnabled }
                .sumOf { it.pendingCount }

            Triple(allItems, synced, pending)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (items, synced, pending) ->
                log.debug { "loadData(): loaded all=${items.size} synced=$synced pending=$pending" }
                errorEvent.onNext("")
                folders.onNext(items)
                totalSyncedCount.onNext(synced)
                totalPendingCount.onNext(pending)
                lastFullSyncAt.onNext(syncPreferences.lastFullSyncAt.value ?: 0L)
            }, { error ->
                log.error(error) { "loadData(): failed" }
                errorEvent.onNext(error.message ?: "Load failed")
            })
    }

    fun addSelectedFolder(bucketId: String, displayName: String, relativePath: String) {
        isLoading.onNext(true)
        Single.fromCallable {
            runBlocking {
                // Deep dedup: if folder was previously disabled, just re-enable it
                val existing = syncFolderDao.getByBucketId(bucketId)
                if (existing != null) {
                    syncFolderDao.setEnabled(bucketId, true)
                } else {
                    syncFolderDao.upsert(
                        SyncFolder(
                            bucketId = bucketId,
                            displayName = displayName,
                            relativePath = relativePath,
                            isEnabled = true,
                            lastSyncAt = null,
                        )
                    )
                }
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                isLoading.onNext(false)
                loadData()
            }, { error ->
                log.error(error) { "addSelectedFolder(): failed" }
                errorEvent.onNext(error.message ?: "Add folder failed")
                isLoading.onNext(false)
            })
            .addTo(disposables)
    }

    /**
     * Called when user clicks the remove checkbox on a folder tag.
     * Emits a confirmation event for the Activity to handle.
     */
    fun removeFolder(bucketId: String, displayName: String) {
        showRemoveFolderConfirmationEvent.onNext(RemoveFolderEvent.Show(bucketId, displayName))
    }

    /**
     * Called from Activity after user confirms folder removal in the dialog.
     * Deep dedup: only disable the folder, don't delete synced records.
     */
    fun confirmRemoveFolder(bucketId: String) {
        showRemoveFolderConfirmationEvent.onNext(RemoveFolderEvent.Dismiss)
        Single.fromCallable {
            runBlocking {
                syncFolderDao.setEnabled(bucketId, false)
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                loadData()
            }, { error ->
                log.error(error) { "confirmRemoveFolder(): failed" }
                errorEvent.onNext(error.message ?: "Remove folder failed")
            })
            .addTo(disposables)
    }

    /** Called from Activity when user cancels folder removal. */
    fun cancelRemoveFolder() {
        showRemoveFolderConfirmationEvent.onNext(RemoveFolderEvent.Dismiss)
        loadData()
    }

    /**
     * Resets the sync state for all enabled folders, so all local files
     * will be treated as "new" and re-uploaded on the next sync.
     * PhotoPrism's server-side SHA-1 dedup prevents actual duplicates.
     */
    fun resyncAll() {
        Single.fromCallable {
            runBlocking {
                val enabledFolders = syncFolderDao.getEnabledFolders()
                if (enabledFolders.isNotEmpty()) {
                    syncedFileDao.deleteByBucketIds(enabledFolders.map { it.bucketId })
                    log.info { "resyncAll(): cleared ${enabledFolders.size} folders' sync state" }
                    true
                } else {
                    log.info { "resyncAll(): no enabled folders to reset" }
                    false
                }
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ cleared ->
                if (cleared) {
                    loadData()
                }
            }, { error ->
                log.error(error) { "resyncAll(): failed" }
                errorEvent.onNext(error.message ?: "Failed to reset sync state")
            })
            .addTo(disposables)
    }

    fun onSyncNowClicked() {
        if (isSyncing.value == true) {
            // Bug 9: If syncing, clicking means "Stop/Pause sync"
            log.info { "onSyncNowClicked(): stopping_sync" }
            workManager.cancelUniqueWork(SyncWorker.TAG)
            isSyncing.onNext(false)
            return
        }

        // Allow sync even when pending is 0 — the Worker will still
        // ensure albums exist on the server (album re-creation fix).

        // If wifiOnly is on, check if we're actually on an unmetered network
        val wifiOnly = syncPreferences.wifiOnly.value ?: true
        if (wifiOnly && !connectivityChecker.isOnUnmeteredNetwork()) {
            // Only show warning when on metered (mobile data) network
            showMeteredDataWarningEvent.onNext(true)
            return
        }

        doEnqueueSync()
    }

    /**
     * Called from Activity after the user confirms syncing on metered data.
     */
    fun onMeteredSyncConfirmed() {
        showMeteredDataWarningEvent.onNext(false)
        doEnqueueSync()
    }

    /** Called from Activity when the user dismisses the metered data warning. */
    fun onMeteredSyncCancelled() {
        showMeteredDataWarningEvent.onNext(false)
    }

    private fun doEnqueueSync() {
        val d = Single.fromCallable {
            runBlocking {
                val enabledFolders = syncFolderDao.getEnabledFolders()
                if (enabledFolders.isEmpty()) {
                    log.warn { "doEnqueueSync(): no_enabled_folders" }
                    return@runBlocking false
                }

                syncPreferences.setLastFullSyncAt(System.currentTimeMillis())

                // Manual sync always uses CONNECTED (user explicitly asked to sync).
                // wifiOnly only applies to automatic periodic syncs (ScheduleSyncUseCase).
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .addTag(SyncWorker.TAG)
                    .build()

                workManager.enqueueUniqueWork(
                    SyncWorker.TAG,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
                log.info { "doEnqueueSync(): sync_work_enqueued" }
                true
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ enqueued ->
                log.debug { "doEnqueueSync(): completed" }
                if (enqueued) {
                    isSyncing.onNext(true)
                }
                errorEvent.onNext("") // clear any previous error
            }, { error ->
                log.error(error) { "doEnqueueSync(): failed" }
                errorEvent.onNext(error.message ?: "Failed to start sync")
            })
        disposables.add(d)
    }

    fun toggleWifiOnly(enabled: Boolean) {
        val d = Single.fromCallable {
            syncPreferences.setWifiOnly(enabled)
        }
            .subscribeOn(Schedulers.io())
            .subscribe()
        disposables.add(d)
    }

    fun setSyncInterval(value: Int) {
        syncPreferences.setSyncIntervalMin(value)
        syncIntervalMin.onNext(value)
        // Re-schedule PeriodicWork with new interval
        if (value > 0) {
            scheduleSyncUseCase()
        } else {
            workManager.cancelUniqueWork(SyncWorker.PERIODIC_TAG)
        }
    }

    private fun loadSyncHistory() {
        Single.fromCallable {
            runBlocking {
                syncHistoryDao.getRecent(10)
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ items ->
                syncHistory.onNext(items.map { item ->
                    SyncHistoryDisplay(
                        startedAt = dateFormat.format(Date(item.startedAt)),
                        syncedCount = item.syncedCount,
                        failedCount = item.failedCount,
                        totalFiles = item.totalFiles,
                        status = item.status,
                        folderName = item.folderName,
                    )
                })
            }, { error ->
                log.error(error) { "loadSyncHistory(): failed" }
            })
            .addTo(disposables)
    }

    fun formatLastSyncTime(timestamp: Long): String {
        if (timestamp <= 0) return "—"
        return dateFormat.format(Date(timestamp))
    }

    fun formatHistoryStatus(status: String): String {
        return when (status) {
            SyncHistoryItem.STATUS_COMPLETED -> "✓"
            SyncHistoryItem.STATUS_PARTIAL -> "⚠"
            SyncHistoryItem.STATUS_FAILED -> "✗"
            else -> status
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadDataDisposable?.dispose()
        workManager.getWorkInfosByTagLiveData(SyncWorker.TAG)
            .removeObserver(syncWorkObserver)
        disposables.clear()
    }
}

/** Sealed interface for remove folder confirmation events. */
sealed interface RemoveFolderEvent {
    /** Show the confirmation dialog for this folder. */
    data class Show(val bucketId: String, val displayName: String) : RemoveFolderEvent
    /** Dismiss (user cancelled or confirmed). */
    data object Dismiss : RemoveFolderEvent
}
