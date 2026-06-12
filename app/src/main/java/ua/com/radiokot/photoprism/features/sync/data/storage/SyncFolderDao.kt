package ua.com.radiokot.photoprism.features.sync.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ua.com.radiokot.photoprism.features.sync.data.model.SyncFolder

@Dao
interface SyncFolderDao {

    @Query("SELECT * FROM sync_folders WHERE isEnabled = 1")
    suspend fun getEnabledFolders(): List<SyncFolder>

    @Query("SELECT * FROM sync_folders")
    suspend fun getAllFolders(): List<SyncFolder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: SyncFolder)

    @Query("UPDATE sync_folders SET lastSyncAt = :timestamp WHERE bucketId = :bucketId")
    suspend fun updateLastSync(bucketId: String, timestamp: Long)

    @Query("UPDATE sync_folders SET isEnabled = :enabled WHERE bucketId = :bucketId")
    suspend fun setEnabled(bucketId: String, enabled: Boolean)

    @Query("SELECT * FROM sync_folders WHERE bucketId = :bucketId")
    suspend fun getByBucketId(bucketId: String): SyncFolder?

    @Query("DELETE FROM sync_folders WHERE bucketId = :bucketId")
    suspend fun delete(bucketId: String)

    @Query("SELECT COUNT(*) FROM sync_folders WHERE isEnabled = 1")
    suspend fun countEnabled(): Int
}
