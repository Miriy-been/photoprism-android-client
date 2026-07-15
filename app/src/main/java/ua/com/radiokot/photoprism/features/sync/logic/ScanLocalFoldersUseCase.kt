package ua.com.radiokot.photoprism.features.sync.logic

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import java.io.File

data class LocalMediaItem(
    val mediaStoreId: Long?,
    val filePath: String,
    val dateModified: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val bucketId: String,
    val displayName: String,
    val contentUri: String,
)

class ScanLocalFoldersUseCase(
    context: Context,
) {
    private val contentResolver: ContentResolver = context.contentResolver
    private val log = kLogger("ScanLocalFoldersUC")

    /**
     * Refreshes the MediaStore cache to ensure queries return current data.
     * On API 30+, this forces a re-scan of the filesystem.
     * Should be called before counting or scanning operations.
     */
    private fun refreshMediaStore() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                contentResolver.refresh(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null)
                contentResolver.refresh(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null)
            }
        } catch (e: Exception) {
            log.warn(e) { "refreshMediaStore(): failed" }
        }
    }

    companion object {
        private val IMAGES_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )

        private val VIDEO_PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.DISPLAY_NAME,
        )
    }

    /**
     * Scans given folders for media files via MediaStore.
     * @param bucketIds set of BUCKET_IDs to scan
     * @return all media items in the specified folders
     */
    fun scan(bucketIds: Set<String>): List<LocalMediaItem> {
        if (bucketIds.isEmpty()) return emptyList()

        val items = mutableListOf<LocalMediaItem>()

        items.addAll(queryMedia(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = IMAGES_PROJECTION,
            bucketIds = bucketIds,
        ))

        items.addAll(queryMedia(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection = VIDEO_PROJECTION,
            bucketIds = bucketIds,
        ))

        return items
    }

    /**
     * Queries all folders that contain media files.
     * Uses HashMap for O(1) dedup, avoids iterating every file.
     * @return list of (bucketId, displayName, relativePath)
     */
    fun getAllMediaFolders(): List<Triple<String, String, String>> {
        val folders = LinkedHashMap<String, Triple<String, String, String>>()
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )

        val uris = arrayOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )

        for (uri in uris) {
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                if (idIdx < 0) return@use

                while (cursor.moveToNext()) {
                    val bucketId = cursor.getString(idIdx) ?: continue
                    if (folders.containsKey(bucketId)) continue

                    val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    folders[bucketId] = Triple(bucketId, displayName ?: bucketId, "")
                }
            }
        }

        return folders.values.toList()
    }

    /**
     * Counts media files in specific folders.
     */
    fun countMediaInFolders(bucketIds: Set<String>): Map<String, Int> {
        if (bucketIds.isEmpty()) return emptyMap()

        val counts = mutableMapOf<String, Int>()
        for (bucketId in bucketIds) {
            counts[bucketId] = 0
        }

        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_ID)

        for (uri in uris) {
            for (bucketId in bucketIds) {
                contentResolver.query(
                    uri,
                    projection,
                    "${MediaStore.Images.Media.BUCKET_ID} = ?",
                    arrayOf(bucketId),
                    null,
                )?.use { cursor ->
                    counts[bucketId] = (counts[bucketId] ?: 0) + cursor.count
                }
            }
        }

        return counts
    }

    /**
     * Finds the default camera/media folder (usually "DCIM/Camera").
     * Queries MediaStore for the most common photo folder and returns
     * (bucketId, displayName, relativePath) or null if no media found.
     *
     * The [displayName] is typically "Camera" and [relativePath] is "DCIM/Camera/".
     */
    fun findDefaultCameraFolder(): Triple<String, String, String>? {
        refreshMediaStore()
        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
            )
        } else {
            arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
            )
        }

        // Try to find "Camera" folder first
        val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf("Camera")

        for (uri in uris) {
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val bucketId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: "Camera"
                    val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getString(2) ?: "DCIM/Camera/"
                    } else {
                        val data = cursor.getString(2)
                        if (data != null) {
                            val rel = data.substringAfter("/0/").substringBeforeLast("/")
                            "$rel/"
                        } else {
                            "DCIM/Camera/"
                        }
                    }
                    log.info { "findDefaultCameraFolder(): found bucketId=$bucketId, relativePath=$relativePath" }
                    return Triple(bucketId, displayName, relativePath)
                }
            }
        }

        log.warn { "findDefaultCameraFolder(): no Camera folder found via MediaStore" }
        return null
    }

    private fun queryMedia(
        uri: android.net.Uri,
        projection: Array<String>,
        bucketIds: Set<String>,
    ): List<LocalMediaItem> {
        val items = mutableListOf<LocalMediaItem>()

        for (bucketId in bucketIds) {
            contentResolver.query(
                uri,
                projection,
                "${MediaStore.Images.Media.BUCKET_ID} = ?",
                arrayOf(bucketId),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val isApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                val relativePathIdx = if (isApi29Plus) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    // On API 29+ the DATA column is deprecated and may be null.
                    // Fall back to RELATIVE_PATH + DISPLAY_NAME.
                    val filePath: String
                    if (dataIdx >= 0) {
                        val data = cursor.getString(dataIdx)
                        if (data != null) {
                            filePath = data
                        } else if (relativePathIdx >= 0) {
                            val relPath = cursor.getString(relativePathIdx)
                            val displayName = cursor.getString(nameIdx)
                            filePath = if (relPath != null && displayName != null) {
                                "$relPath/$displayName"
                            } else {
                                continue
                            }
                        } else {
                            continue
                        }
                    } else if (relativePathIdx >= 0) {
                        val relPath = cursor.getString(relativePathIdx)
                        val displayName = cursor.getString(nameIdx)
                        filePath = if (relPath != null && displayName != null) {
                            "$relPath/$displayName"
                        } else {
                            continue
                        }
                    } else {
                        continue
                    }

                    items.add(
                        LocalMediaItem(
                            mediaStoreId = cursor.getLong(idIdx),
                            filePath = filePath,
                            dateModified = cursor.getLong(dateIdx),
                            sizeBytes = cursor.getLong(sizeIdx),
                            mimeType = cursor.getString(mimeIdx) ?: "image/*",
                            bucketId = cursor.getString(bucketIdx) ?: "",
                            displayName = cursor.getString(nameIdx) ?: "unknown",
                            contentUri = "${uri}/${cursor.getLong(idIdx)}",
                        )
                    )
                }
            }
        }

        return items
    }

    /**
     * Counts new (not yet synced) files in a folder by its relative path.
     * Uses MediaStore RELATIVE_PATH matching with BUCKET_DISPLAY_NAME fallback.
     */
    fun countNewFilesByPath(relativePath: String, syncedFileDao: SyncedFileDao): Int {
        refreshMediaStore()
        val mediaItems = mutableListOf<LocalMediaItem>()

        queryMediaByPath(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = IMAGES_PROJECTION,
            relativePath = relativePath,
            destination = mediaItems,
        )
        queryMediaByPath(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection = VIDEO_PROJECTION,
            relativePath = relativePath,
            destination = mediaItems,
        )

        if (mediaItems.isEmpty()) {
            // Bug 2 fix: Fallback to BUCKET_DISPLAY_NAME when RELATIVE_PATH query returns 0.
            // Some devices/vendors may store paths differently in RELATIVE_PATH.
            val folderName = relativePath.trimEnd('/').split('/').lastOrNull()
            if (folderName != null) {
                log.warn {
                    "countNewFilesByPath: RELATIVE_PATH returned 0 " +
                            "for path='$relativePath', " +
                            "falling back to BUCKET_DISPLAY_NAME='$folderName'"
                }
                queryMediaByDisplayName(
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection = IMAGES_PROJECTION,
                    displayName = folderName,
                    destination = mediaItems,
                )
                queryMediaByDisplayName(
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection = VIDEO_PROJECTION,
                    displayName = folderName,
                    destination = mediaItems,
                )
            } else {
                log.warn {
                    "countNewFilesByPath: RELATIVE_PATH returned 0 " +
                            "for path='$relativePath', " +
                            "could not extract folder name for fallback"
                }
            }
        }

        if (mediaItems.isEmpty()) {
            log.warn { "countNewFilesByPath: all_queries_returned_0_for_path='$relativePath'" }
            return 0
        }

        // Filter out files that no longer exist on disk (deleted by user externally).
        // This keeps the pending count accurate even if MediaStore hasn't been updated.
        val existingItems = mediaItems.filter { item ->
            try {
                File(item.filePath).exists() ||
                        contentResolver.openInputStream(Uri.parse(item.contentUri))?.use { true } == true
            } catch (e: Exception) {
                false
            }
        }
        val removedCount = mediaItems.size - existingItems.size
        if (removedCount > 0) {
            log.info {
                "countNewFilesByPath: filtered_out_$removedCount deleted files " +
                        "for path='$relativePath'"
            }
        }

        return runBlocking {
            var count = 0
            for (item in existingItems) {
                val exists = if (item.mediaStoreId != null) {
                    syncedFileDao.countByMediaStoreId(item.mediaStoreId) > 0
                } else {
                    syncedFileDao.countByPathAndDate(item.filePath, item.dateModified) > 0
                }
                if (!exists) count++
            }
            log.info {
                "countNewFilesByPath: path='$relativePath', " +
                        "mediaItems=${existingItems.size}, new=$count"
            }
            count
        }
    }

    /**
     * Scans media files in a folder by its relative path.
     * Returns only files not yet synced.
     * Falls back to BUCKET_DISPLAY_NAME if RELATIVE_PATH query returns 0.
     */
    fun scanByRelativePath(relativePath: String, syncedFileDao: SyncedFileDao): List<LocalMediaItem> {
        refreshMediaStore()
        val allItems = mutableListOf<LocalMediaItem>()

        queryMediaByPath(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = IMAGES_PROJECTION,
            relativePath = relativePath,
            destination = allItems,
        )
        queryMediaByPath(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection = VIDEO_PROJECTION,
            relativePath = relativePath,
            destination = allItems,
        )

        // Fallback to BUCKET_DISPLAY_NAME when RELATIVE_PATH query returns 0
        if (allItems.isEmpty()) {
            val folderName = relativePath.trimEnd('/').split('/').lastOrNull()
            if (folderName != null) {
                log.warn {
                    "scanByRelativePath: RELATIVE_PATH returned 0 " +
                            "for path='$relativePath', " +
                            "falling back to BUCKET_DISPLAY_NAME='$folderName'"
                }
                queryMediaByDisplayName(
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection = IMAGES_PROJECTION,
                    displayName = folderName,
                    destination = allItems,
                )
                queryMediaByDisplayName(
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection = VIDEO_PROJECTION,
                    displayName = folderName,
                    destination = allItems,
                )
            }
        }

        // Filter out files that no longer exist on disk (deleted by user externally).
        // This keeps the scan result consistent with countNewFilesByPath and prevents
        // repeated upload failures for deleted files.
        val existingItems = allItems.filter { item ->
            try {
                File(item.filePath).exists() ||
                        contentResolver.openInputStream(Uri.parse(item.contentUri))?.use { true } == true
            } catch (e: Exception) {
                false
            }
        }
        val removedCount = allItems.size - existingItems.size
        if (removedCount > 0) {
            log.info {
                "scanByRelativePath: filtered_out $removedCount deleted files " +
                        "for path='$relativePath'"
            }
        }

        return runBlocking {
            existingItems.filter { item ->
                val exists = if (item.mediaStoreId != null) {
                    syncedFileDao.countByMediaStoreId(item.mediaStoreId) > 0
                } else {
                    syncedFileDao.countByPathAndDate(item.filePath, item.dateModified) > 0
                }
                !exists
            }
        }
    }

    private fun queryMediaByPath(
        uri: Uri,
        projection: Array<String>,
        relativePath: String,
        destination: MutableList<LocalMediaItem>,
    ) {
        // RELATIVE_PATH available API 29+. Fall back to DATA column matching.
        val useRelativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            null // will use DATA-based matching
        }

        val selection: String?
        val selectionArgs: Array<String>
        if (useRelativePath == true) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("${relativePath}%")
        } else {
            // DATA column stores absolute paths like /storage/emulated/0/DCIM/Camera/IMG.jpg
            // so we need %path% matching, not prefix matching
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%${relativePath}%")
        }

        val before = destination.size
        contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val relativePathIdx = if (useRelativePath == true) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                // On API 29+ the DATA column is deprecated and may be null (scoped storage).
                // Fall back to RELATIVE_PATH + DISPLAY_NAME as a consistent identifier.
                val filePath: String
                if (dataIdx >= 0) {
                    val data = cursor.getString(dataIdx)
                    if (data != null) {
                        filePath = data
                    } else if (relativePathIdx >= 0) {
                        val relPath = cursor.getString(relativePathIdx)
                        val displayName = cursor.getString(nameIdx)
                        filePath = if (relPath != null && displayName != null) {
                            "$relPath/$displayName"
                        } else {
                            log.warn { "queryMediaByPath: skipped — RELATIVE_PATH or DISPLAY_NAME is null" }
                            continue
                        }
                    } else {
                        log.warn { "queryMediaByPath: skipped — DATA is null and no RELATIVE_PATH available" }
                        continue
                    }
                } else if (relativePathIdx >= 0) {
                    val relPath = cursor.getString(relativePathIdx)
                    val displayName = cursor.getString(nameIdx)
                    filePath = if (relPath != null && displayName != null) {
                        "$relPath/$displayName"
                    } else {
                        log.warn { "queryMediaByPath: skipped — RELATIVE_PATH or DISPLAY_NAME is null (no DATA column)" }
                        continue
                    }
                } else {
                    log.warn { "queryMediaByPath: skipped — no DATA column and no RELATIVE_PATH available" }
                    continue
                }

                destination.add(
                    LocalMediaItem(
                        mediaStoreId = cursor.getLong(idIdx),
                        filePath = filePath,
                        dateModified = cursor.getLong(dateIdx),
                        sizeBytes = cursor.getLong(sizeIdx),
                        mimeType = cursor.getString(mimeIdx) ?: "image/*",
                        bucketId = cursor.getString(bucketIdx) ?: "",
                        displayName = cursor.getString(nameIdx) ?: "unknown",
                        contentUri = "${uri}/${cursor.getLong(idIdx)}",
                    )
                )
            }
        }
        log.info {
            "queryMediaByPath: uri=$uri, " +
                    "selection=$selection, " +
                    "args=${selectionArgs.contentToString()}, " +
                    "returned=${destination.size - before}"
        }
    }

    /**
     * Queries media files by BUCKET_DISPLAY_NAME (fallback for countNewFilesByPath).
     * Used when RELATIVE_PATH-based query returns 0 results.
     */
    private fun queryMediaByDisplayName(
        uri: Uri,
        projection: Array<String>,
        displayName: String,
        destination: MutableList<LocalMediaItem>,
    ) {
        val before = destination.size
        contentResolver.query(
            uri,
            projection,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val isApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val relativePathIdx = if (isApi29Plus) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val filePath: String
                if (dataIdx >= 0) {
                    val data = cursor.getString(dataIdx)
                    if (data != null) {
                        filePath = data
                    } else if (relativePathIdx >= 0) {
                        val relPath = cursor.getString(relativePathIdx)
                        val dispName = cursor.getString(nameIdx)
                        filePath = if (relPath != null && dispName != null) {
                            "$relPath/$dispName"
                        } else {
                            log.warn { "queryMediaByDisplayName: skipped — RELATIVE_PATH or DISPLAY_NAME is null" }
                            continue
                        }
                    } else {
                        log.warn { "queryMediaByDisplayName: skipped — DATA is null and no RELATIVE_PATH" }
                        continue
                    }
                } else if (relativePathIdx >= 0) {
                    val relPath = cursor.getString(relativePathIdx)
                    val dispName = cursor.getString(nameIdx)
                    filePath = if (relPath != null && dispName != null) {
                        "$relPath/$dispName"
                    } else {
                        log.warn { "queryMediaByDisplayName: skipped — RELATIVE_PATH or DISPLAY_NAME is null (no DATA)" }
                        continue
                    }
                } else {
                    log.warn { "queryMediaByDisplayName: skipped — no DATA and no RELATIVE_PATH" }
                    continue
                }

                destination.add(
                    LocalMediaItem(
                        mediaStoreId = cursor.getLong(idIdx),
                        filePath = filePath,
                        dateModified = cursor.getLong(dateIdx),
                        sizeBytes = cursor.getLong(sizeIdx),
                        mimeType = cursor.getString(mimeIdx) ?: "image/*",
                        bucketId = cursor.getString(bucketIdx) ?: "",
                        displayName = cursor.getString(nameIdx) ?: "unknown",
                        contentUri = "${uri}/${cursor.getLong(idIdx)}",
                    )
                )
            }
        }
        log.info {
            "queryMediaByDisplayName: uri=$uri, " +
                    "displayName=$displayName, " +
                    "returned=${destination.size - before}"
        }
    }
}
