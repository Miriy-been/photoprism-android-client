package ua.com.radiokot.photoprism.features.sync.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_folders")
data class SyncFolder(
    @PrimaryKey val bucketId: String,
    val displayName: String,
    val relativePath: String,
    val isEnabled: Boolean,
    val lastSyncAt: Long?,
)
