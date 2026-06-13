package ua.com.radiokot.photoprism.features.labels.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "labels_cache",
    indices = [
        Index("cachedAt"),
    ]
)
data class LabelCacheEntity(
    @PrimaryKey
    val uid: String,
    val name: String,
    val slug: String,
    val isFavorite: Boolean = false,
    val itemCount: Int = 0,
    val thumbnailHash: String?,
    val cachedAt: Long,
)
