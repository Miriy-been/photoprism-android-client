package ua.com.radiokot.photoprism.features.sync.logic

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.rxjava3.RxWorker
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.scope.Scope
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.features.importt.logic.ImportFilesUseCase
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.sync.data.model.SyncHistoryItem
import ua.com.radiokot.photoprism.features.sync.data.model.SyncedFile
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncFolderDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncHistoryItemDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import ua.com.radiokot.photoprism.features.sync.view.SyncNotificationsManager
import ua.com.radiokot.photoprism.api.albums.model.PhotoPrismAlbumCreation
import ua.com.radiokot.photoprism.api.albums.service.PhotoPrismAlbumsService
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SyncResult(val synced: Int, val failed: Int)
data class FolderResult(val synced: Int, val failed: Int)

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : RxWorker(appContext, workerParams),
    KoinComponent {

    private val sessionScope: Scope?
        get() = getKoin().getScopeOrNull(DI_SCOPE_SESSION)
    private val log = kLogger("SyncWorker")

    // Lazily injected dependencies so they're available in processFolder which
    // runs on Schedulers.io() (not the main thread).
    private val importFilesUseCase: ImportFilesUseCase? by lazy {
        sessionScope?.get<ImportFilesUseCase>()
    }
    private val scanUseCase: ScanLocalFoldersUseCase by inject()
    private val syncedFileDao: SyncedFileDao by inject()
    private val syncFolderDao: SyncFolderDao by inject()
    private val syncHistoryDao: SyncHistoryItemDao by inject()
    private val notificationsManager: SyncNotificationsManager by inject()

    override fun createWork(): Single<Result> {
        if (importFilesUseCase == null) {
            log.error { "createWork(): no_session_scope_cannot_upload" }
            if (!isStopped) {
                notificationsManager.notifySyncFailed(
                    uploadToken = "nosession",
                    syncedCount = 0,
                    failedCount = 0,
                )
            }
            return Single.just(Result.failure())
        }

        log.info { "createWork(): session_scope_obtained_import_usecase_ready" }

        val uploadToken = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        var folderNames = ""

        // Clean up stale pending records so previously paused files are re-scanned as new.
        runBlocking {
            syncedFileDao.cleanupPending(System.currentTimeMillis())
        }

        return Single.fromCallable {
            runBlocking { syncFolderDao.getEnabledFolders() }
        }
            .subscribeOn(Schedulers.io())
            .flatMap { enabledFolders ->
                folderNames = enabledFolders.joinToString(", ") { it.displayName }
                if (enabledFolders.isEmpty()) {
                    log.info { "createWork(): no_enabled_folders" }
                    return@flatMap Single.just(SyncResult(0, 0))
                }

                // Show "Preparing…" during scan & album creation phase
                notificationsManager.notifyPreparing()

                var chained = Single.just(SyncResult(0, 0))
                var folderIndex = 0

                for (folder in enabledFolders) {
                    val f = folder
                    val idx = folderIndex
                    chained = chained.flatMap { current ->
                        log.info {
                            "createWork(): processing_folder:" +
                                    "folder=${f.displayName}, bucketId=${f.bucketId}"
                        }

                        processFolder(
                            folder = f,
                            uploadToken = uploadToken,
                            folderIndex = idx,
                            totalFolders = enabledFolders.size,
                            syncedSoFar = current.synced,
                        )
                            .map { folderResult ->
                                SyncResult(
                                    synced = current.synced + folderResult.synced,
                                    failed = current.failed + folderResult.failed,
                                )
                            }
                    }
                    folderIndex++
                }

                chained
            }
            .map { result ->
                // Write sync history
                val now = System.currentTimeMillis()
                val historyStatus = when {
                    result.failed > 0 && result.synced > 0 -> SyncHistoryItem.STATUS_PARTIAL
                    result.failed > 0 -> SyncHistoryItem.STATUS_FAILED
                    else -> SyncHistoryItem.STATUS_COMPLETED
                }
                val totalFilesHistory = result.synced + result.failed
                runBlocking {
                    syncHistoryDao.insert(
                        SyncHistoryItem(
                            startedAt = startedAt,
                            finishedAt = now,
                            syncedCount = result.synced,
                            failedCount = result.failed,
                            totalFiles = totalFilesHistory,
                            folderCount = 1.coerceAtLeast(folderNames.split(", ").size),
                            folderName = folderNames.ifEmpty { null },
                            status = historyStatus,
                        )
                    )
                    // Clean up history older than 30 days
                    syncHistoryDao.deleteOlderThan(now - 30L * 24 * 60 * 60 * 1000)
                }

                // Clean up stale pending records older than 1 hour
                runBlocking {
                    syncedFileDao.cleanupPending(
                        System.currentTimeMillis() - 60 * 60 * 1000
                    )
                }

                if (!isStopped) {
                    when {
                        result.failed > 0 && result.synced > 0 -> {
                            notificationsManager.notifySyncFailed(
                                uploadToken = uploadToken,
                                syncedCount = result.synced,
                                failedCount = result.failed,
                            )
                        }
                        result.failed > 0 -> {
                            notificationsManager.notifySyncFailed(
                                uploadToken = uploadToken,
                                syncedCount = 0,
                                failedCount = result.failed,
                            )
                        }
                        result.synced > 0 -> {
                            notificationsManager.notifySyncComplete(
                                uploadToken = uploadToken,
                                fileCount = result.synced,
                            )
                        }
                        else -> {
                            // Nothing happened (e.g. album re-creation only)
                            notificationsManager.cancelSyncNotification()
                        }
                    }
                }

                log.info {
                    "createWork(): completed: synced=${result.synced}, failed=${result.failed}"
                }

                if (result.failed > 0 && result.synced == 0) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
            .onErrorReturn { error ->
                log.error(error) { "createWork(): error_occurred" }

                // Write a failed history entry so the user has visibility.
                try {
                    val now = System.currentTimeMillis()
                    runBlocking {
                        syncHistoryDao.insert(
                            SyncHistoryItem(
                                startedAt = startedAt,
                                finishedAt = now,
                                syncedCount = 0,
                                failedCount = 0,
                                totalFiles = 0,
                                folderCount = 0,
                                folderName = folderNames.ifEmpty { null },
                                status = SyncHistoryItem.STATUS_FAILED,
                            )
                        )
                    }
                } catch (e: Exception) {
                    log.warn(e) { "createWork(): failed_to_write_error_history" }
                }

                if (!isStopped) {
                    notificationsManager.notifySyncFailed(
                        uploadToken = uploadToken,
                        syncedCount = 0,
                        failedCount = 0,
                    )
                }
                Result.retry()
            }
    }

    /**
     * Processes a single folder: scan for new files, upload them all at once
     * via [ImportFilesUseCase], mark completed, and match PhotoPrism hashes.
     *
     * Progress is driven by [ImportFilesUseCase.Status] Observable, which
     * is throttled to at most one update every 500ms and fed to both the
     * foreground notification and the WorkManager progress API — keeping
     * the two always in sync.
     */
    private fun processFolder(
        folder: ua.com.radiokot.photoprism.features.sync.data.model.SyncFolder,
        uploadToken: String,
        folderIndex: Int,
        totalFolders: Int,
        syncedSoFar: Int,
    ): Single<FolderResult> {
        val worker = this

        return Single.fromCallable {
            // --- Ensure album exists on the server ---
            val destinationAlbums = try {
                val albumsService = sessionScope?.get<PhotoPrismAlbumsService>()
                if (albumsService != null) {
                    val createdAlbum = runBlocking {
                        albumsService.createAlbum(
                            PhotoPrismAlbumCreation(folder.displayName)
                        )
                    }
                    log.info {
                        "processFolder(): album_created:" +
                                "title=${folder.displayName}, uid=${createdAlbum.uid}"
                    }
                    setOf(DestinationAlbum.Existing(createdAlbum.uid, createdAlbum.title))
                } else {
                    setOf(DestinationAlbum.ToCreate(folder.displayName))
                }
            } catch (e: Exception) {
                log.info {
                    "processFolder(): album_create_fallback_to_tocreate:" +
                            "title=${folder.displayName}, error=${e.message}"
                }
                setOf(DestinationAlbum.ToCreate(folder.displayName))
            }

            // --- Scan for new files ---
            val newFiles = scanUseCase.scanByRelativePath(folder.relativePath, syncedFileDao)
                .filter { it.filePath.isNotBlank() }

            log.info {
                "processFolder(): scan_result:" +
                        "folder=${folder.displayName}," +
                        "path=${folder.relativePath}," +
                        "new=${newFiles.size}"
            }

            if (newFiles.isEmpty()) {
                return@fromCallable FolderResult(0, 0)
            }

            // --- Pre-write pending records ---
            runBlocking {
                syncedFileDao.insertAll(
                    newFiles.map { item ->
                        SyncedFile(
                            mediaStoreId = item.mediaStoreId,
                            filePath = item.filePath,
                            dateModified = item.dateModified,
                            sizeBytes = item.sizeBytes,
                            mimeType = item.mimeType,
                            bucketId = folder.bucketId,
                            syncedAt = System.currentTimeMillis(),
                            status = SyncedFile.STATUS_PENDING,
                        )
                    }
                )
            }

            val importableFiles = newFiles.map { item ->
                ImportableFile(
                    contentUri = item.contentUri,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    size = item.sizeBytes,
                    treeUri = folder.bucketId,
                )
            }

            // --- Upload all files at once via ImportFilesUseCase ---
            // Progress from ImportFilesUseCase drives both the notification bar
            // AND the WorkManager progress API, keeping them perfectly in sync.
            val totalInFolder = importableFiles.size
            var syncedInFolder = 0
            // Track the last upload-estimated count so ProcessingUpload doesn't
            // prematurely push the count to totalInFolder (the server may still
            // be indexing).
            var lastEstimatedTotal = syncedSoFar

            try {
                importFilesUseCase!!(
                    files = importableFiles,
                    albums = destinationAlbums,
                    uploadToken = "${uploadToken}_${folderIndex}",
                )
                    .throttleLast(500, TimeUnit.MILLISECONDS)
                    .doOnNext { status ->
                        if (isStopped) return@doOnNext

                        when (status) {
                            is ImportFilesUseCase.Status.Uploading -> {
                                val percent = status.percent
                                val estimatedInFolder = if (percent >= 0) {
                                    (percent / 100.0 * totalInFolder).toInt()
                                        .coerceIn(0, totalInFolder)
                                } else {
                                    0
                                }
                                lastEstimatedTotal = syncedSoFar + estimatedInFolder

                                // Update foreground notification
                                notificationsManager.notifySyncProgress(
                                    uploadToken = uploadToken,
                                    folderName = folder.displayName,
                                    percent = percent,
                                    syncedCount = lastEstimatedTotal,
                                    totalFolders = totalFolders,
                                    folderIndex = folderIndex,
                                )

                                // Update WorkManager progress → App UI
                                worker.setProgressAsync(
                                    androidx.work.Data.Builder()
                                        .putInt("synced_count", lastEstimatedTotal)
                                        .putInt("total_files", totalInFolder)
                                        .putDouble("upload_percent", percent)
                                        .putString("folder_name", folder.displayName)
                                        .putInt("folder_index", folderIndex)
                                        .putInt("total_folders", totalFolders)
                                        .build()
                                )
                            }

                            is ImportFilesUseCase.Status.ProcessingUpload -> {
                                // Server-side indexing phase — show indeterminate progress
                                notificationsManager.notifySyncProcessing(
                                    uploadToken = uploadToken,
                                    folderName = folder.displayName,
                                    syncedCount = lastEstimatedTotal,
                                    totalFolders = totalFolders,
                                    folderIndex = folderIndex,
                                )

                                worker.setProgressAsync(
                                    androidx.work.Data.Builder()
                                        .putInt("synced_count", lastEstimatedTotal)
                                        .putInt("total_files", totalInFolder)
                                        .putDouble("upload_percent", -2.0) // sentinel: processing
                                        .putString("folder_name", folder.displayName)
                                        .putInt("folder_index", folderIndex)
                                        .putInt("total_folders", totalFolders)
                                        .build()
                                )
                            }
                        }
                    }
                    .ignoreElements()
                    .blockingAwait(60, TimeUnit.MINUTES)

                // All files uploaded AND server-side processing confirmed.
                syncedInFolder = totalInFolder

                runBlocking {
                    syncedFileDao.markCompleted(newFiles.map { it.filePath })
                    syncFolderDao.updateLastSync(
                        bucketId = folder.bucketId,
                        timestamp = System.currentTimeMillis()
                    )
                }

                log.info {
                    "processFolder(): all_synced:" +
                            "folder=${folder.displayName}, count=$syncedInFolder"
                }

                // Hash matching is deferred to after the folder result is returned,
                // so the user sees "sync complete" immediately rather than waiting
                // for PhotoPrism to finish indexing and searching (which can take
                // up to 15 seconds per file with retries).
            } catch (e: Exception) {
                // Upload failed. Clean up pending records so these files are
                // properly treated as "new" on the next sync run.
                runBlocking {
                    syncedFileDao.deleteByFilePaths(newFiles.map { it.filePath })
                }

                log.error(e) {
                    "processFolder(): upload_failed:" +
                            "folder=${folder.displayName}, error=${e.message}"
                }

                return@fromCallable FolderResult(0, importableFiles.size)
            }

            // Fire-and-forget hash matching: runs on a separate IO thread so it
            // doesn't block the sync completion notification to the user.
            io.reactivex.rxjava3.core.Completable.fromAction {
                try {
                    val matchUseCase = sessionScope?.get<MatchSyncedFilesUseCase>()
                    if (matchUseCase != null) {
                        matchUseCase.match(
                            filePaths = newFiles.map { it.filePath }
                        ).blockingGet()
                        log.debug { "processFolder(): hash_matching_completed" }
                    }
                } catch (e: Exception) {
                    log.warn(e) { "processFolder(): hash_matching_error: ${e.message}" }
                }
            }
                .subscribeOn(Schedulers.io())
                .subscribe()

            FolderResult(syncedInFolder, 0)
        }.subscribeOn(Schedulers.io())
    }

    override fun getForegroundInfo(): Single<ForegroundInfo> {
        val notificationsManager: SyncNotificationsManager = getKoin().get()
        val notification = notificationsManager.getSyncProgressNotification()
        val notificationId = SyncNotificationsManager.SYNC_PROGRESS_NOTIFICATION_ID

        return Single.just(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ForegroundInfo(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            else
                ForegroundInfo(notificationId, notification)
        )
    }

    companion object {
        const val TAG = "Sync"
        const val PERIODIC_TAG = "SyncPeriodic"
    }
}
