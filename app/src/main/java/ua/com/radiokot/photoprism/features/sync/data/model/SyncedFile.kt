package ua.com.radiokot.photoprism.features.sync.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "synced_files",
    indices = [
        Index("mediaStoreId"),
        Index("filePath", "dateModified")
    ]
)
data class SyncedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long?,
    val filePath: String,
    val dateModified: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val bucketId: String,
    val syncedAt: Long,
)
