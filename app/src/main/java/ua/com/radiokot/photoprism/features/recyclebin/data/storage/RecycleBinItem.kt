package ua.com.radiokot.photoprism.features.recyclebin.data.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recycle_bin_items")
data class RecycleBinItem(
    @PrimaryKey
    val photoUid: String,
    val archivedAt: Long,
    val thumbnailHash: String?,
    val photoTitle: String?,
)