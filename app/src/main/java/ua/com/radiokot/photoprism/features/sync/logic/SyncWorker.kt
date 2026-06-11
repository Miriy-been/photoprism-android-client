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
import ua.com.radiokot.photoprism.features.sync.data.model.SyncedFile
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncFolderDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import ua.com.radiokot.photoprism.features.sync.view.SyncNotificationsManager
import java.util.concurrent.TimeUnit

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
        val deduplicateUseCase: CompareAndDeduplicateUseCase by inject()
        val syncedFileDao: SyncedFileDao by inject()
        val syncFolderDao: SyncFolderDao by inject()
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

        val uploadToken = System.currentTimeMillis().toString()
        var totalSynced = 0
        var totalFailed = 0

        return Single.fromCallable {
            runBlocking { syncFolderDao.getEnabledFolders() }
        }
            .subscribeOn(Schedulers.io())
            .flatMap { enabledFolders ->
                if (enabledFolders.isEmpty()) {
                    log.info { "createWork(): no_enabled_folders" }
                    return@flatMap Single.just(Pair(totalSynced, totalFailed))
                }

                // Calculate total pending files across all folders
                val totalPending = enabledFolders.sumOf { folder ->
                    runBlocking {
                        scanUseCase.countNewFilesByPath(folder.relativePath, syncedFileDao)
                    }
                }

                if (totalPending == 0) {
                    log.info { "createWork(): no_pending_files" }
                    return@flatMap Single.just(Pair(totalSynced, totalFailed))
                }

                var result = Single.just(Pair(0, 0))

                for (folder in enabledFolders) {
                    result = result.flatMap { (synced, failed) ->
                        totalSynced = synced
                        totalFailed = failed

                        log.info {
                            "createWork(): processing_folder:" +
                                    "\nfolder=${folder.displayName}," +
                                    "\nbucketId=${folder.bucketId}"
                        }

                        notificationsManager.notifySyncProgress(
                            uploadToken = uploadToken,
                            folderName = folder.displayName,
                            syncedCount = totalSynced,
                        )

                        processFolder(
                            folder = folder,
                            importFilesUseCase = importFilesUseCase,
                            scanUseCase = scanUseCase,
                            deduplicateUseCase = deduplicateUseCase,
                            syncedFileDao = syncedFileDao,
                            syncFolderDao = syncFolderDao,
                            uploadToken = uploadToken,
                        )
                            .map { folderResult ->
                                Pair(
                                    totalSynced + folderResult.first,
                                    totalFailed + folderResult.second
                                )
                            }
                    }
                }

                result
            }
            .map { (synced, failed) ->
                totalSynced = synced
                totalFailed = failed

                if (!isStopped) {
                    if (totalFailed > 0) {
                        notificationsManager.notifySyncFailed(
                            uploadToken = uploadToken,
                            syncedCount = totalSynced,
                            failedCount = totalFailed,
                        )
                    } else if (totalSynced > 0) {
                        notificationsManager.notifySyncComplete(
                            uploadToken = uploadToken,
                            fileCount = totalSynced,
                        )
                    }
                }

                log.info {
                    "createWork(): completed:" +
                            "\nsynced=$totalSynced," +
                            "\nfailed=$totalFailed"
                }

                if (totalFailed > 0 && totalSynced == 0) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
            .doOnSubscribe {
                log.info { "createWork(): starting" }
                notificationsManager.notifySyncProgress(
                    uploadToken = uploadToken,
                    folderName = null,
                    syncedCount = 0,
                )
            }
            .onErrorReturn { error ->
                log.error(error) { "createWork(): error_occurred" }
                if (!isStopped) {
                    notificationsManager.notifySyncFailed(
                        uploadToken = uploadToken,
                        syncedCount = totalSynced,
                        failedCount = totalFailed,
                    )
                }
                Result.retry()
            }
    }

    private fun processFolder(
        folder: ua.com.radiokot.photoprism.features.sync.data.model.SyncFolder,
        importFilesUseCase: ImportFilesUseCase,
        scanUseCase: ScanLocalFoldersUseCase,
        deduplicateUseCase: CompareAndDeduplicateUseCase,
        syncedFileDao: SyncedFileDao,
        syncFolderDao: SyncFolderDao,
        uploadToken: String,
    ): Single<Pair<Int, Int>> {
        return Single.fromCallable {
            // Step 1: Scan folder by relative path (SAF-picked folders use path, not bucket ID)
            val newFiles = scanUseCase.scanByRelativePath(folder.relativePath, syncedFileDao)
                .filter { it.filePath.isNotBlank() }

            log.info {
                "processFolder(): scan_result:" +
                        "folder=${folder.displayName}," +
                        "path=${folder.relativePath}," +
                        "new=${newFiles.size}"
            }

            if (newFiles.isEmpty()) {
                return@fromCallable Pair(0, 0)
            }

            // Step 3: Convert to ImportableFile and upload in batches
            val importableFiles = newFiles.map { item ->
                ImportableFile(
                    contentUri = item.contentUri,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    size = item.sizeBytes,
                    treeUri = folder.bucketId,
                )
            }

            val albums = setOf<DestinationAlbum>(
                DestinationAlbum.ToCreate(folder.displayName)
            )

            var syncedInFolder = 0
            var failedInFolder = 0

            // Upload in batches of BATCH_SIZE
            val batches = importableFiles.chunked(BATCH_SIZE)
            log.info {
                "processFolder(): starting_upload:" +
                        "folder=${folder.displayName}," +
                        "total_files=${importableFiles.size}," +
                        "batches=${batches.size}"
            }

            for (batchIndex in batches.indices) {
                val batch = batches[batchIndex]
                log.info {
                    "processFolder(): processing_batch:" +
                            "folder=${folder.displayName}," +
                            "batch=$batchIndex," +
                            "batch_size=${batch.size}"
                }

                try {
                    log.debug {
                        "processFolder(): calling_import_usecase:" +
                                "folder=${folder.displayName}," +
                                "batch=$batchIndex," +
                                "files=${batch.joinToString { it.displayName }}"
                    }

                    importFilesUseCase(
                        files = batch,
                        albums = albums,
                        uploadToken = "${uploadToken}_${folder.bucketId.toBase64()}_${batchIndex}",
                    ).ignoreElements().blockingAwait(30, TimeUnit.MINUTES)

                    log.debug {
                        "processFolder(): import_usecase_completed:" +
                                "folder=${folder.displayName}," +
                                "batch=$batchIndex"
                    }

                    // Record synced files
                    val syncedEntries = batch.mapIndexed { i, file ->
                        val item = newFiles[batchIndex * BATCH_SIZE + i]
                        SyncedFile(
                            mediaStoreId = item.mediaStoreId,
                            filePath = item.filePath,
                            dateModified = item.dateModified,
                            sizeBytes = item.sizeBytes,
                            mimeType = item.mimeType,
                            bucketId = item.bucketId,
                            syncedAt = System.currentTimeMillis(),
                        )
                    }

                    runBlocking {
                        syncedFileDao.insertAll(syncedEntries)
                    }

                    syncedInFolder += batch.size
                    log.info {
                        "processFolder(): batch_uploaded:" +
                                "folder=${folder.displayName}," +
                                "batch=$batchIndex," +
                                "count=${batch.size}"
                    }
                } catch (e: Exception) {
                    failedInFolder += batch.size
                    log.error(e) {
                        "processFolder(): batch_failed:" +
                                "folder=${folder.displayName}," +
                                "batch=$batchIndex," +
                                "error=${e.message}," +
                                "stack=${e.stackTraceToString().take(1000)}"
                    }
                }
            }

            log.info {
                "processFolder(): upload_completed:" +
                        "folder=${folder.displayName}," +
                        "synced=$syncedInFolder," +
                        "failed=$failedInFolder"
            }

            // Update lastSyncAt
            runBlocking {
                syncFolderDao.updateLastSync(
                    bucketId = folder.bucketId,
                    timestamp = System.currentTimeMillis()
                )
            }

            Pair(syncedInFolder, failedInFolder)
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
        private const val BATCH_SIZE = 10

        private fun String.toBase64(): String =
            android.util.Base64.encodeToString(
                toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            )
    }
}
