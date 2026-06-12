package ua.com.radiokot.photoprism.features.sync.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "synced_files",
    indices = [
        Index("mediaStoreId"),
        Index("filePath", "dateModified"),
        Index("status"),
        Index("bucketId"),
        Index("photoPrismHash"),
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
    @ColumnInfo(defaultValue = "completed")
    val status: String = STATUS_COMPLETED,
    /**
     * PhotoPrism file hash, set after the uploaded file is indexed
     * and matched via the PhotoPrism API. Used to link local files
     * to their server-side media entries for offline viewing.
     */
    val photoPrismHash: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETED = "completed"
    }
}
