package ua.com.radiokot.photoprism.base.util

import com.squareup.picasso.Downloader
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import java.io.File
import java.io.IOException

/**
 * A [Downloader] that tries local sources before falling back to the network.
 *
 * Resolution order for any image URL:
 * 1. [ThumbnailDiskCache] — pre-downloaded thumbnail files keyed by hash
 * 2. [SyncedFileDao] — locally synced original files matched by PhotoPrism hash
 * 3. Network (delegate) — fetch from PhotoPrism server, then cache to [ThumbnailDiskCache]
 */
class LocalFirstDownloader(
    private val delegate: Downloader,
    private val thumbnailDiskCache: ThumbnailDiskCache,
    private val syncedFileDao: SyncedFileDao,
) : Downloader {

    private val log = kLogger("LocalFirstDownloader")

    companion object {
        // URL patterns: /v1/t/{hash}/tile_xxx or /v1/t/{hash}/fit_xxx or /v1/videos/{hash}/...
        private val HASH_IN_URL_REGEX = Regex(
            """[/]t/ ([a-f0-9]+) [/]|[/]videos/ ([a-f0-9]+) [/]""".replace("\\s", "")
        )
    }

    @Throws(IOException::class)
    override fun load(request: Request): Response {
        val url = request.url.toString()
        val hash = extractHash(url)

        // 1. Try local caches if a hash was extracted.
        if (hash != null) {
            // a) ThumbnailDiskCache
            thumbnailDiskCache.getFile(hash)?.let { file ->
                log.debug { "load(): cache_hit_thumbnailDiskCache: hash=$hash" }
                return responseFromFile(file, request)
            }

            // b) SyncedFile original file
            try {
                val localPath = runBlocking {
                    syncedFileDao.getFilePathByHash(hash)
                }
                if (localPath != null) {
                    val localFile = File(localPath)
                    if (localFile.exists()) {
                        log.debug { "load(): cache_hit_syncedFile: hash=$hash" }
                        return responseFromFile(localFile, request)
                    }
                }
            } catch (e: Exception) {
                log.warn(e) { "load(): syncedFileDao_query_failed: hash=$hash" }
            }
        }

        // 2. Try network via delegate.
        val response = try {
            delegate.load(request)
        } catch (e: IOException) {
            throw e
        }

        // 3. On success, asynchronously cache to ThumbnailDiskCache for future offline use.
        if (hash != null && response.isSuccessful) {
            cacheToDiskAsync(hash, response)
        }

        return response
    }

    override fun shutdown() {
        delegate.shutdown()
    }

    /**
     * Creates an [okhttp3.Response] from a local [File].
     */
    private fun responseFromFile(file: File, request: Request): Response {
        val mediaType = guessMediaType(file.name).toMediaTypeOrNull()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(file.readBytes().toResponseBody(mediaType))
            .build()
    }

    /**
     * Reads the response body and saves it to [ThumbnailDiskCache].
     */
    private fun cacheToDiskAsync(hash: String, response: Response) {
        try {
            val bytes = response.body?.bytes() ?: return
            thumbnailDiskCache.save(hash, bytes)
            log.debug { "cacheToDiskAsync(): cached: hash=$hash" }
        } catch (e: Exception) {
            log.warn(e) { "cacheToDiskAsync(): failed: hash=$hash" }
        }
    }

    /**
     * Guesses the [MediaType][okhttp3.MediaType] from a file name extension.
     */
    private fun guessMediaType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".webp") -> "image/webp"
            fileName.endsWith(".gif") -> "image/gif"
            fileName.endsWith(".mp4") -> "video/mp4"
            fileName.endsWith(".mov") -> "video/quicktime"
            else -> "image/jpeg"
        }
    }

    /**
     * Extracts the PhotoPrism file hash from a URL like
     * `{apiUrl}/v1/t/{hash}/tile_xxx` or `{apiUrl}/v1/videos/{hash}/...`
     */
    private fun extractHash(url: String): String? {
        return HASH_IN_URL_REGEX.find(url)?.let { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        }
    }
}
