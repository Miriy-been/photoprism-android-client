package ua.com.radiokot.photoprism.base.util

import android.net.Uri
import java.io.File

/**
 * Manages locally cached thumbnail files on disk.
 *
 * Thumbnails are stored as `{hash}.jpg` files in [thumbnailsDir],
 * keyed by the PhotoPrism media file hash.
 */
class ThumbnailDiskCache(
    private val thumbnailsDir: File,
) {
    init {
        thumbnailsDir.mkdirs()
    }

    /**
     * Returns a `file://` [Uri] for a locally cached thumbnail,
     * or `null` if it hasn't been downloaded yet.
     */
    fun getUri(hash: String): Uri? {
        val file = getFile(hash)
        return if (file != null) Uri.fromFile(file) else null
    }

    /**
     * Returns the [File] for a cached thumbnail if it exists on disk,
     * or `null` otherwise.
     */
    fun getFile(hash: String): File? {
        val file = File(thumbnailsDir, "$hash.jpg")
        return file.takeIf { it.exists() }
    }

    /**
     * Returns the absolute path for a cached thumbnail if it exists,
     * or `null` otherwise.
     */
    fun getPath(hash: String): String? {
        return getFile(hash)?.absolutePath
    }

    /**
     * Saves raw thumbnail bytes to disk and returns the resulting [File].
     */
    fun save(hash: String, bytes: ByteArray): File {
        val file = File(thumbnailsDir, "$hash.jpg")
        thumbnailsDir.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    /**
     * Returns the number of cached thumbnail files on disk.
     */
    fun getCount(): Int =
        thumbnailsDir.listFiles()?.size ?: 0

    /**
     * Deletes all cached thumbnail files.
     */
    fun clear() {
        thumbnailsDir.listFiles()?.forEach { it.delete() }
    }
}
