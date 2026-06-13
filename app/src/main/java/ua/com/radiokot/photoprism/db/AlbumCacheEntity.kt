package ua.com.radiokot.photoprism.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums_cache",
    indices = [
        Index("type"),
        Index("cachedAt"),
    ]
)
data class AlbumCacheEntity(
    @PrimaryKey
    val uid: String,
    val type: String,
    val title: String,
    val thumbnailHash: String?,
    val photoCount: Int,
    val path: String?,
    val ymd: String?,
    val cachedAt: Long,
)
