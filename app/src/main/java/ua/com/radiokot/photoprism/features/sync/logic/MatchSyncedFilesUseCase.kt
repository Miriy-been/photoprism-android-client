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
     * Extracts the plain filename (e.g. "IMG_20230101_120000.jpg")
     * from a full or relative file path.
     */
    private fun extractFilename(filePath: String): String {
        return File(filePath).name
    }

    data class MatchingResult(
        val matched: Int,
    )
}
