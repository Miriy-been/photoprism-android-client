package ua.com.radiokot.photoprism.features.sync.logic

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao

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
     * Uses MediaStore RELATIVE_PATH matching.
     */
    fun countNewFilesByPath(relativePath: String, syncedFileDao: SyncedFileDao): Int {
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

        if (mediaItems.isEmpty()) return 0

        return runBlocking {
            var count = 0
            for (item in mediaItems) {
                val exists = if (item.mediaStoreId != null) {
                    syncedFileDao.countByMediaStoreId(item.mediaStoreId) > 0
                } else {
                    syncedFileDao.countByPathAndDate(item.filePath, item.dateModified) > 0
                }
                if (!exists) count++
            }
            count
        }
    }

    /**
     * Scans media files in a folder by its relative path.
     * Returns only files not yet synced.
     */
    fun scanByRelativePath(relativePath: String, syncedFileDao: SyncedFileDao): List<LocalMediaItem> {
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

        return runBlocking {
            allItems.filter { item ->
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
    }
}
