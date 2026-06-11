package ua.com.radiokot.photoprism.features.sync.view.model

import androidx.lifecycle.ViewModel
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.sync.data.model.SyncFolder
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncFolderDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncPreferencesOnPrefs
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import ua.com.radiokot.photoprism.features.sync.logic.ScanLocalFoldersUseCase
import ua.com.radiokot.photoprism.features.sync.logic.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FolderItem(
    val bucketId: String,
    val displayName: String,
    val relativePath: String,
    val isEnabled: Boolean,
    val pendingCount: Int = 0,
)

class SyncSettingsViewModel(
    private val scanLocalFoldersUseCase: ScanLocalFoldersUseCase,
    private val syncedFileDao: SyncedFileDao,
    private val syncFolderDao: SyncFolderDao,
    private val syncPreferences: SyncPreferencesOnPrefs,
    private val workManager: WorkManager,
) : ViewModel() {

    val folders = BehaviorSubject.createDefault<List<FolderItem>>(emptyList())
    val totalSyncedCount = BehaviorSubject.createDefault(0)
    val totalPendingCount = BehaviorSubject.createDefault(0)
    val lastFullSyncAt = BehaviorSubject.createDefault(0L)
    val wifiOnly = syncPreferences.wifiOnly
    val isLoading = BehaviorSubject.createDefault(false)
    val isSyncing = BehaviorSubject.createDefault(false)
    val errorEvent = BehaviorSubject.create<String>()

    private val log = kLogger("SyncSettingsVM")
    private val disposables = CompositeDisposable()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    init {
        loadData()
        observeSyncWorkerStatus()
    }

    private fun observeSyncWorkerStatus() {
        workManager.getWorkInfosByTagLiveData(SyncWorker.TAG)
            .observeForever { workInfos ->
                val isRunning = workInfos.any {
                    it.state == WorkInfo.State.RUNNING || 
                    it.state == WorkInfo.State.ENQUEUED
                }
                isSyncing.onNext(isRunning)
                
                if (!isRunning) {
                    loadData()
                }
            }
    }

    fun loadData() {
        Single.fromCallable {
            val enabledFolders = runBlocking {
                syncFolderDao.getEnabledFolders()
            }
            val items = enabledFolders.map { folder ->
                val pendingCount = runBlocking {
                    scanLocalFoldersUseCase.countNewFilesByPath(folder.relativePath, syncedFileDao)
                }
                FolderItem(
                    bucketId = folder.bucketId,
                    displayName = folder.displayName,
                    relativePath = folder.relativePath,
                    isEnabled = folder.isEnabled,
                    pendingCount = pendingCount,
                )
            }
            val synced = runBlocking { syncedFileDao.getCount() }
            val pending = items.sumOf { it.pendingCount }
            Triple(items, synced, pending)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (items, synced, pending) ->
                log.debug { "loadData(): loaded folders=${items.size} synced=$synced pending=$pending" }
                errorEvent.onNext("")
                folders.onNext(items)
                totalSyncedCount.onNext(synced)
                totalPendingCount.onNext(pending)
                lastFullSyncAt.onNext(syncPreferences.lastFullSyncAt.value ?: 0L)
            }, { error ->
                log.error(error) { "loadData(): failed" }
                errorEvent.onNext(error.message ?: "Load failed")
            })
            .addTo(disposables)
    }

    fun addSelectedFolder(bucketId: String, displayName: String, relativePath: String) {
        isLoading.onNext(true)
        Single.fromCallable {
            runBlocking {
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

    fun removeFolder(bucketId: String) {
        Single.fromCallable {
            runBlocking {
                syncFolderDao.delete(bucketId)
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                loadData()
            }, { error ->
                log.error(error) { "removeFolder(): failed" }
                errorEvent.onNext(error.message ?: "Remove folder failed")
            })
            .addTo(disposables)
    }

    fun onSyncNowClicked() {
        if (isSyncing.value == true) {
            log.info { "onSyncNowClicked(): skipped_already_syncing" }
            errorEvent.onNext("Sync is already in progress")
            return
        }

        val currentPending = totalPendingCount.value ?: 0
        if (currentPending <= 0) {
            log.info { "onSyncNowClicked(): skipped_pending_is_zero" }
            return
        }

        isSyncing.onNext(true)

        val d = Single.fromCallable {
            runBlocking {
                val enabledFolders = syncFolderDao.getEnabledFolders()
                if (enabledFolders.isNotEmpty()) {
                    syncPreferences.setLastFullSyncAt(System.currentTimeMillis())

                    // Enqueue a one-time sync worker
                    val request = OneTimeWorkRequestBuilder<SyncWorker>()
                        .addTag(SyncWorker.TAG)
                        .build()

                    workManager.enqueueUniqueWork(
                        SyncWorker.TAG,
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
                }
            }
        }
            .subscribeOn(Schedulers.io())
            .subscribe()
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

    fun formatLastSyncTime(timestamp: Long): String {
        if (timestamp <= 0) return "—"
        return dateFormat.format(Date(timestamp))
    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
    }
}
