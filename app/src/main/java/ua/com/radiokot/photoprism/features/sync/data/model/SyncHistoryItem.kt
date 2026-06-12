package ua.com.radiokot.photoprism.features.sync.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_history",
    indices = [
        Index("startedAt"),
    ]
)
data class SyncHistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    val syncedCount: Int,
    val failedCount: Int,
    val totalFiles: Int,
    val folderCount: Int,
    val status: String,  // "completed", "partial", "failed"
) {
    companion object {
        const val STATUS_COMPLETED = "completed"
        const val STATUS_PARTIAL = "partial"
        const val STATUS_FAILED = "failed"
    }
}
