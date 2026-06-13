package ua.com.radiokot.photoprism.features.sync.logic

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.rxjava3.RxWorker
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

    override fun createWork(): Single<Result> {
        val importFilesUseCase = sessionScope?.get<ImportFilesUseCase>()
        val scanUseCase: ScanLocalFoldersUseCase by inject()
        val syncedFileDao: SyncedFileDao by inject()
        val syncFolderDao: SyncFolderDao by inject()
        val syncHistoryDao: SyncHistoryItemDao by inject()
        val notificationsManager: SyncNotificationsManager by inject()

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
        var startedAt = System.currentTimeMillis()
        var enabledFolderCount = 0
        var folderNames = ""
        val accumulatedProgress = notificationsManager.getSyncProgress()

        return Single.fromCallable {
            runBlocking { syncFolderDao.getEnabledFolders() }
        }
            .subscribeOn(Schedulers.io())
            .flatMap { enabledFolders ->
                enabledFolderCount = enabledFolders.size
                folderNames = enabledFolders.joinToString(", ") { it.displayName }
                if (enabledFolders.isEmpty()) {
                    log.info { "createWork(): no_enabled_folders" }
                    return@flatMap Single.just(SyncResult(0, 0))
                }

                // Feature 1: Calculate total pending files for progress tracking
                val totalPending = enabledFolders.sumOf { folder ->
                    runBlocking {
                        scanUseCase.countNewFilesByPath(folder.relativePath, syncedFileDao)
                    }
                }

                log.info {
                    "createWork(): total_pending=$totalPending," +
                            "folders=${enabledFolders.size}"
                }
                // Note: Do NOT exit early when totalPending == 0.
                // processFolder() now handles album (re-)creation before checking for new files,
                // so we need to visit each folder even when there are 0 pending files.

                var chained = Single.just(SyncResult(0, 0))
                if (accumulatedProgress > 0) {
                    log.info {
                        "createWork(): accumulated_progress=$accumulatedProgress" +
                                " (resuming after pause)"
                    }
                }

                for (folder in enabledFolders) {
                    val innerFolder = folder
                    chained = chained.flatMap { current ->
                        log.info {
                            "createWork(): processing_folder:" +
                                    "\nfolder=${innerFolder.displayName}," +
                                    "\nbucketId=${innerFolder.bucketId}"
                        }

                        processFolder(
                            folder = innerFolder,
                            importFilesUseCase = importFilesUseCase,
                            scanUseCase = scanUseCase,
                            syncedFileDao = syncedFileDao,
                            syncFolderDao = syncFolderDao,
                            notificationsManager = notificationsManager,
                            uploadToken = uploadToken,
                            totalFiles = accumulatedProgress + totalPending,
                            alreadySynced = current.synced,
                            accumulatedProgress = accumulatedProgress,
                        )
                            .map { folderResult ->
                                SyncResult(
                                    synced = current.synced + folderResult.synced,
                                    failed = current.failed + folderResult.failed,
                                )
                            }
                    }
                }

                chained
            }
            .map { result ->
                // Clean up stale pending records older than 1 hour
                runBlocking {
                    syncedFileDao.cleanupPending(
                        System.currentTimeMillis() - 60 * 60 * 1000
                    )
                }

                // When nothing was actually synced or failed (e.g. all files already
                // on server, or folder is empty), silently exit without notification
                // and without recording history, similar to Google Photos behavior.
                if (result.synced == 0 && result.failed == 0) {
                    notificationsManager.cancelSyncNotification()
                    notificationsManager.saveSyncProgress(0)
                    log.info {
                        "createWork(): completed_silently:" +
                                "\nnothing_to_sync"
                    }
                    return@map Result.success()
                }

                // Feature 3: Write sync history record
                val now = System.currentTimeMillis()
                val historyStatus = when {
                    result.failed > 0 && result.synced > 0 -> SyncHistoryItem.STATUS_PARTIAL
                    result.failed > 0 -> SyncHistoryItem.STATUS_FAILED
                    else -> SyncHistoryItem.STATUS_COMPLETED
                }
                val totalFiles = result.synced + result.failed
                val displayFileCount = accumulatedProgress + result.synced
                // Always record history, even for 0-file syncs (album re-creation).
                runBlocking {
                    syncHistoryDao.insert(
                        SyncHistoryItem(
                            startedAt = startedAt,
                            finishedAt = now,
                            syncedCount = result.synced,
                            failedCount = result.failed,
                            totalFiles = totalFiles,
                            folderCount = enabledFolderCount,
                            folderName = folderNames.ifEmpty { null },
                            status = historyStatus,
                        )
                    )
                    // Clean up history older than 30 days
                    syncHistoryDao.deleteOlderThan(now - 30L * 24 * 60 * 60 * 1000)
                }

                if (!isStopped) {
                    when {
                        result.failed > 0 && result.synced > 0 -> {
                            notificationsManager.notifySyncFailed(
                                uploadToken = uploadToken,
                                syncedCount = displayFileCount,
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
                        else -> {
                            notificationsManager.notifySyncComplete(
                                uploadToken = uploadToken,
                                fileCount = displayFileCount,
                            )
                            // Clear accumulated progress on successful completion
                            // so the next sync starts fresh.
                            notificationsManager.saveSyncProgress(0)
                        }
                    }
                }

                log.info {
                    "createWork(): completed:" +
                            "\nsynced=${result.synced}," +
                            "\nfailed=${result.failed}"
                }

                if (result.failed > 0 && result.synced == 0) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
            .doOnSubscribe {
                log.info { "createWork(): starting" }
                // Show "Preparing…" initially so the user doesn't
                // see "0 photos synced" during scan/dedup phase.
                notificationsManager.notifyPreparing()
            }
            .onErrorReturn { error ->
                log.error(error) { "createWork(): error_occurred" }
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

    private fun processFolder(
        folder: ua.com.radiokot.photoprism.features.sync.data.model.SyncFolder,
        importFilesUseCase: ImportFilesUseCase,
        scanUseCase: ScanLocalFoldersUseCase,
        syncedFileDao: SyncedFileDao,
        syncFolderDao: SyncFolderDao,
        notificationsManager: SyncNotificationsManager,
        uploadToken: String,
        totalFiles: Int,
        alreadySynced: Int,
        accumulatedProgress: Int = 0,
    ): Single<FolderResult> {
        return Single.fromCallable {
            // Bug 1 fix: Ensure album exists on the server BEFORE checking for new files.
            // This way, even when the album was deleted on the web UI, it gets re-created.
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
                                "title=${folder.displayName}," +
                                "uid=${createdAlbum.uid}"
                    }
                    setOf(
                        DestinationAlbum.Existing(createdAlbum.uid, createdAlbum.title)
                    )
                } else {
                    setOf(DestinationAlbum.ToCreate(folder.displayName))
                }
            } catch (e: Exception) {
                log.info {
                    "processFolder(): album_create_fallback_to_tocreate:" +
                            "title=${folder.displayName}," +
                            "error=${e.message}"
                }
                setOf(DestinationAlbum.ToCreate(folder.displayName))
            }

            val newFiles = scanUseCase.scanByRelativePath(folder.relativePath, syncedFileDao)
                .filter { it.filePath.isNotBlank() }

            log.info {
                "processFolder(): scan_result:" +
                        "folder=${folder.displayName}," +
                        "path=${folder.relativePath}," +
                        "new=${newFiles.size}"
            }

            if (newFiles.isEmpty()) {
                log.info { "processFolder(): no_new_files_album_ensured" }
                return@fromCallable FolderResult(0, 0)
            }

            // Feature: Server-side deduplication for reinstall scenarios.
            // If the local DB has been wiped (app reinstall), MediaStore files
            // will show as "new" even if they were already synced before.
            //
            // Three-stage dedup strategy:
            //   1. Search by original filename (original:"xxx") — fast, matches most files
            //   2. Compute SHA1 locally + search by hash (hash:xxx) — reliable but slower
            //   3. Fallback: upload and let the server detect duplicates by SHA1
            val serverDeduplicatedFiles = mutableListOf<LocalMediaItem>()
            var skippedByServer = 0

            val matchUseCase = sessionScope?.get<MatchSyncedFilesUseCase>()
            if (matchUseCase != null) {
                for (item in newFiles) {
                    val filename = matchUseCase.extractFilename(item.filePath)

                    // Search by original filename — fast, matches most camera files.
                    // If filename search fails, let the server detect duplicates
                    // via SHA1 during import, avoiding expensive local file reads.
                    val serverHash = matchUseCase.searchByFilenameForDeduplication(filename)

                    if (serverHash != null) {
                        // Server already has this file — mark as completed locally
                        // so we don't re-upload it.
                        runBlocking {
                            syncedFileDao.insertAll(
                                listOf(
                                    SyncedFile(
                                        mediaStoreId = item.mediaStoreId,
                                        filePath = item.filePath,
                                        dateModified = item.dateModified,
                                        sizeBytes = item.sizeBytes,
                                        mimeType = item.mimeType,
                                        bucketId = folder.bucketId,
                                        syncedAt = System.currentTimeMillis(),
                                        status = SyncedFile.STATUS_COMPLETED,
                                        photoPrismHash = serverHash,
                                    )
                                )
                            )
                        }
                        skippedByServer++
                        log.info {
                            "processFolder(): server_dedup_skipped:" +
                                    "filePath=${item.filePath}," +
                                    "hash=$serverHash"
                        }
                    } else {
                        serverDeduplicatedFiles.add(item)
                    }
                }
                log.info {
                    "processFolder(): server_dedup_result:" +
                            "total=${newFiles.size}," +
                            "skipped=$skippedByServer," +
                            "toUpload=${serverDeduplicatedFiles.size}"
                }
            } else {
                serverDeduplicatedFiles.addAll(newFiles)
            }

            if (serverDeduplicatedFiles.isEmpty()) {
                log.info {
                    "processFolder(): all_files_skipped_by_server_dedup:" +
                            "skipped=$skippedByServer"
                }
                return@fromCallable FolderResult(0, 0)
            }

            // Pre-write pending records for all files that passed dedup.
            // This ensures pause/resume doesn't re-dedup them — on resume,
            // these files are already in syncedFileDao and won't be scanned.
            runBlocking {
                syncedFileDao.insertAll(
                    serverDeduplicatedFiles.map { item ->
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

            val importableFiles = serverDeduplicatedFiles.map { item ->
                ImportableFile(
                    contentUri = item.contentUri,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    size = item.sizeBytes,
                    treeUri = folder.bucketId,
                )
            }

            var syncedInFolder = 0
            var failedInFolder = 0

            val batches = importableFiles.chunked(BATCH_SIZE)

            for (batchIndex in batches.indices) {
                if (isStopped) {
                    log.info { "processFolder(): worker_stopped_aborting" }
                    break
                }

                val batch = batches[batchIndex]
                // Index into serverDeduplicatedFiles (not newFiles) so
                // batch indexing correctly aligns with importableFiles.
                val batchFiles = serverDeduplicatedFiles
                    .drop(batchIndex * BATCH_SIZE)
                    .take(batch.size)

                // Pending records are pre-written above for all dedup-passed files,
                // so we only update status here.

                try {
                    importFilesUseCase(
                        files = batch,
                        albums = destinationAlbums,
                        uploadToken = "${uploadToken}_${folder.bucketId.toBase64()}_${batchIndex}",
                    ).ignoreElements().blockingAwait(30, TimeUnit.MINUTES)

                    runBlocking {
                        syncedFileDao.markCompleted(batchFiles.map { it.filePath })
                    }

                    // Match PhotoPrism hashes for this batch in the background.
                    try {
                        val matchUseCase = sessionScope?.get<MatchSyncedFilesUseCase>()
                        if (matchUseCase != null) {
                            matchUseCase.match(
                                filePaths = batchFiles.map { it.filePath }
                            ).subscribe(
                                { result ->
                                    log.debug {
                                        "processFolder(): hash_matching:" +
                                                "\nmatched=${result.matched}"
                                    }
                                },
                                { error ->
                                    log.warn(error) {
                                        "processFolder(): hash_matching_failed"
                                    }
                                }
                            )
                        }
                    } catch (e: Exception) {
                        log.warn(e) { "processFolder(): hash_matching_error" }
                    }

                    syncedInFolder += batch.size

                    // Feature 1: Update progress notification with determinate progress
                    // accumulatedProgress carries over counts from previous runs (pause/resume),
                    // so the notification shows the real total across all runs.
                    val displaySynced = accumulatedProgress + alreadySynced + syncedInFolder
                    notificationsManager.saveSyncProgress(displaySynced)
                    notificationsManager.notifySyncProgress(
                        uploadToken = uploadToken,
                        folderName = folder.displayName,
                        syncedCount = displaySynced,
                        totalFiles = totalFiles,
                    )

                    log.info {
                        "processFolder(): batch_succeeded:" +
                                "folder=${folder.displayName}," +
                                "batch=$batchIndex," +
                                "count=${batch.size}"
                    }
                } catch (e: Exception) {
                    // Bug fix: Do NOT mark failed files as 'completed'.
                    // Doing so would prevent re-upload attempts and can create
                    // duplicate server entries if the upload actually succeeded
                    // but the response timed out (ImportFilesUseCase retries
                    // internally up to 6 times).

                    // Clean up pending records for the failed batch so these
                    // files are properly treated as "new" on the next sync run,
                    // preventing the same file from being double-counted
                    // (once as "failed", once as "synced" on retry).
                    runBlocking {
                        syncedFileDao.deleteByFilePaths(batchFiles.map { it.filePath })
                    }

                    failedInFolder += batch.size

                    log.error(e) {
                        "processFolder(): batch_failed:" +
                                "folder=${folder.displayName}," +
                                "batch=$batchIndex," +
                                "error=${e.message}"
                    }
                }
            }

            runBlocking {
                syncFolderDao.updateLastSync(
                    bucketId = folder.bucketId,
                    timestamp = System.currentTimeMillis()
                )
            }

            log.info {
                "processFolder(): folder_result:" +
                        "folder=${folder.displayName}," +
                        "syncedInFolder=$syncedInFolder," +
                        "skippedByServer=$skippedByServer," +
                        "failedInFolder=$failedInFolder"
            }
            FolderResult(syncedInFolder, failedInFolder)
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
        private const val BATCH_SIZE = 3

        private fun String.toBase64(): String =
            android.util.Base64.encodeToString(
                toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            )
    }
}
