package ua.com.radiokot.photoprism.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cached_media",
    indices = [
        Index("takenAtLocal"),
        Index("favorite"),
        Index("cachedAt"),
    ]
)
data class CachedMediaEntity(
    @PrimaryKey
    val uid: String,
    val title: String?,
    val description: String?,
    val mediaType: String,
    val mediaTypeRaw: String?,
    val takenAt: String?,
    val takenAtLocal: String?,
    val timeZone: String?,
    val favorite: Boolean = false,
    val isPrivate: Boolean = false,
    val lat: Double?,
    val lng: Double?,
    val altitude: Double?,
    val cameraModel: String?,
    val cameraMake: String?,
    val width: Int?,
    val height: Int?,
    val fileHash: String?,
    val cachedAt: Long,
    val isReadOnly: Boolean = false,
    /**
     * Path to a locally cached thumbnail file on disk,
     * or null if the thumbnail hasn't been downloaded yet.
     */
    val localThumbnailPath: String? = null,
)
