package ua.com.radiokot.photoprism.features.people.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "people_cache",
    indices = [
        Index("cachedAt"),
    ]
)
data class PeopleCacheEntity(
    @PrimaryKey
    val id: String,
    val name: String?,
    val isFavorite: Boolean = false,
    val photoCount: Int = 0,
    val thumbnailHash: String?,
    val cachedAt: Long,
)
