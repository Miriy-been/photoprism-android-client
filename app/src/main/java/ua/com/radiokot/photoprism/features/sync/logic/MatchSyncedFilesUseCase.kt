package ua.com.radiokot.photoprism.features.sync.logic

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.sync.data.model.SyncedFile
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Matches recently uploaded [SyncedFile]s to their PhotoPrism file hashes
 * by searching the PhotoPrism API with the original filename.
 *
 * After a file is uploaded and indexed by PhotoPrism, there is a short delay
 * before it becomes searchable. This use case retries with backoff.
 */
class MatchSyncedFilesUseCase(
    private val syncedFileDao: SyncedFileDao,
    private val photoPrismPhotosService: PhotoPrismPhotosService,
) {
    private val log = kLogger("MatchSyncedFilesUC")

    companion object {
        /** Maximum number of retries when searching for a file. */
        private const val MAX_RETRIES = 5

        /** Delay between retries in seconds. */
        private const val RETRY_DELAY_S = 3L

        /** Maximum number of unmatched files to process per invocation. */
        private const val MAX_FILES_PER_RUN = 50
    }

    /**
     * Finds un-matched synced files and attempts to match them
     * to their PhotoPrism hashes by searching with the original filename.
     *
     * @param filePaths optional list of specific file paths to match.
     * If null, all recently completed files with null hash are processed.
     */
    fun match(
        filePaths: List<String>? = null,
    ): Single<MatchingResult> {
        return Single.fromCallable {
            val unmatchedFiles = if (filePaths != null) {
                // We can't easily query by specific file paths + null hash in a single query,
                // so we simply return the file paths and let the caller handle matching.
                return@fromCallable matchByPaths(filePaths)
            } else {
                runBlocking {
                    syncedFileDao.getCompletedWithNullHash(limit = MAX_FILES_PER_RUN)
                }
            }

            matchFiles(unmatchedFiles)
        }
            .subscribeOn(Schedulers.io())
    }

    /**
     * Matches files by their file paths (called after a batch upload).
     */
    private fun matchByPaths(filePaths: List<String>): MatchingResult {
        if (filePaths.isEmpty()) return MatchingResult(0)

        var matched = 0
        var failed = 0

        for (filePath in filePaths) {
            val hash = searchHashByFilename(
                filename = extractFilename(filePath),
                depth = 0,
            )

            if (hash != null) {
                runBlocking {
                    syncedFileDao.updatePhotoPrismHashByPaths(listOf(filePath), hash)
                }
                matched++
                log.debug {
                    "matchByPaths(): matched:" +
                            "\nfilePath=$filePath," +
                            "\nhash=$hash"
                }
            } else {
                failed++
                log.warn {
                    "matchByPaths(): not_found:" +
                            "\nfilePath=$filePath"
                }
            }
        }

        return MatchingResult(matched = matched)
    }

    /**
     * Matches a list of [SyncedFile] records by searching PhotoPrism for each filename.
     */
    private fun matchFiles(files: List<SyncedFile>): MatchingResult {
        if (files.isEmpty()) return MatchingResult(0)

        var matched = 0

        for (syncedFile in files) {
            val filename = extractFilename(syncedFile.filePath)

            val hash = searchHashByFilename(
                filename = filename,
                depth = 0,
            )

            if (hash != null) {
                runBlocking {
                    syncedFileDao.updatePhotoPrismHash(
                        id = syncedFile.id,
                        hash = hash,
                    )
                }
                matched++
                log.debug {
                    "matchFiles(): matched:" +
                            "\nid=${syncedFile.id}," +
                            "\nfilePath=${syncedFile.filePath}," +
                            "\nhash=$hash"
                }
            } else {
                log.warn {
                    "matchFiles(): not_found_after_retries:" +
                            "\nid=${syncedFile.id}," +
                            "\nfilePath=${syncedFile.filePath}"
                }
            }
        }

        return MatchingResult(matched = matched)
    }

    /**
     * Searches PhotoPrism by filename, retrying with delay if no match is found.
     */
    private fun searchHashByFilename(
        filename: String,
        depth: Int,
    ): String? {
        if (depth >= MAX_RETRIES) return null

        try {
            // PhotoPrism search query supports searching by filename/name.
            val results = photoPrismPhotosService.getMergedPhotos(
                count = 10,
                offset = 0,
                q = filename,
            )

            // Match by checking if any file in the results has a name containing the filename.
            for (photo in results) {
                for (file in photo.files) {
                    if (file.name.contains(filename, ignoreCase = true)) {
                        return file.hash
                    }
                }
            }

            // Also try matching by photo hash (the primary hash).
            for (photo in results) {
                if (photo.hash.isNotEmpty()) {
                    // Try name-based match on the photo title.
                    if (photo.title.contains(filename, ignoreCase = true)) {
                        return photo.hash
                    }
                }
            }
        } catch (e: Exception) {
            log.warn(e) {
                "searchHashByFilename(): api_error:" +
                        "\nfilename=$filename," +
                        "\ndepth=$depth"
            }
        }

        // No match yet — wait and retry.
        if (depth < MAX_RETRIES - 1) {
            TimeUnit.SECONDS.sleep(RETRY_DELAY_S)
            return searchHashByFilename(filename, depth + 1)
        }

        return null
    }

    /**
     * Low-latency search for deduplication pre-checks.
     * Uses the `original:` and `name:` search filters supported by PhotoPrism
     * instead of the plain `q=` full-text search (which searches titles, not filenames).
     */
    private fun searchHashByFilenameFast(
        filename: String,
        depth: Int,
    ): String? {
        if (depth >= 2) return null

        try {
            // Strategy 1: Search by original filename using the `original:` filter.
            // PhotoPrism preserves the `OriginalName` during import, so this is the
            // most direct way to find a file that was previously uploaded.
            var results = photoPrismPhotosService.getMergedPhotos(
                count = 50,
                offset = 0,
                q = "original:\"$filename\"",
            )

            // Match by checking if any file has the same original name.
            for (photo in results) {
                for (file in photo.files) {
                    if (file.originalName != null &&
                        file.originalName.equals(filename, ignoreCase = true)
                    ) {
                        return file.hash
                    }
                }
            }

            // Strategy 2: If exact original name search found nothing,
            // try a broader name filter (without path/extension).
            if (results.isEmpty()) {
                val basename = filename.substringBeforeLast(".")
                results = photoPrismPhotosService.getMergedPhotos(
                    count = 50,
                    offset = 0,
                    q = "name:\"$basename*\"",
                )

                for (photo in results) {
                    for (file in photo.files) {
                        if (file.originalName != null &&
                            file.originalName.contains(filename, ignoreCase = true)
                        ) {
                            return file.hash
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.warn(e) {
                "searchHashByFilenameFast(): api_error: filename=$filename, depth=$depth"
            }
        }

        if (depth < 1) {
            TimeUnit.SECONDS.sleep(RETRY_DELAY_S)
            return searchHashByFilenameFast(filename, depth + 1)
        }

        return null
    }

    /**
     * Computes the SHA-1 hash of a file on disk.
     *
     * @param filePath absolute path to the file
     * @return the lowercase hex SHA-1 string, or null if the file cannot be read
     */
    fun computeSha1(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) return null

            val digest = java.security.MessageDigest.getInstance("SHA-1")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            log.warn(e) {
                "computeSha1(): failed: filePath=$filePath"
            }
            null
        }
    }

    /**
     * Searches PhotoPrism by SHA-1 hash.
     *
     * This is the most reliable dedup method since PhotoPrism internally
     * identifies duplicate files by their SHA-1 hash.
     *
     * @param sha1 the lowercase hex SHA-1 hash string
     * @return the PhotoPrism file hash if found, or null
     */
    fun searchBySha1(sha1: String): String? {
        try {
            val results = photoPrismPhotosService.getMergedPhotos(
                count = 10,
                offset = 0,
                q = "hash:$sha1",
            )

            // Match by file hash (most specific)
            for (photo in results) {
                for (file in photo.files) {
                    if (file.hash.equals(sha1, ignoreCase = true)) {
                        return file.hash
                    }
                }
            }

            // Also try matching by photo hash (less specific but still useful)
            for (photo in results) {
                if (photo.hash.equals(sha1, ignoreCase = true)) {
                    return photo.hash
                }
            }
        } catch (e: Exception) {
            log.warn(e) {
                "searchBySha1(): api_error: sha1=$sha1"
            }
        }

        return null
    }

    /**
     * Searches the PhotoPrism server for a file by its filename
     * and returns the hash if found, or null if not found.
     *
     * Used for server-side deduplication when the local DB has been lost
     * (e.g., after app reinstall).
     *
     * @param filename the plain filename (e.g. "IMG_20230101_120000.jpg")
     * @return the PhotoPrism hash of the matching file, or null if not found
     */
    fun searchByFilename(filename: String): String? {
        return searchHashByFilename(filename = filename, depth = 0)
    }

    /**
     * Low-latency version of [searchByFilename] for deduplication pre-checks.
     * Only retries once with a short delay — fast enough for pre-upload checks.
     */
    fun searchByFilenameForDeduplication(filename: String): String? {
        return searchHashByFilenameFast(filename = filename, depth = 0)
    }

    /**
     * Extracts the plain filename (e.g. "IMG_20230101_120000.jpg")
     * from a full or relative file path.
     */
    fun extractFilename(filePath: String): String {
        return File(filePath).name
    }

    data class MatchingResult(
        val matched: Int,
    )
}
