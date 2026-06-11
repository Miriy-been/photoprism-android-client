package ua.com.radiokot.photoprism.features.sync.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.com.radiokot.photoprism.features.sync.data.model.SyncedFile

@Dao
interface SyncedFileDao {

    @Query("SELECT COUNT(*) FROM synced_files WHERE mediaStoreId = :mediaStoreId AND mediaStoreId IS NOT NULL")
    suspend fun countByMediaStoreId(mediaStoreId: Long): Int

    @Query("SELECT COUNT(*) FROM synced_files WHERE filePath = :path AND dateModified = :date")
    suspend fun countByPathAndDate(path: String, date: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<SyncedFile>)

    @Query("DELETE FROM synced_files WHERE bucketId = :bucketId")
    suspend fun deleteByBucketId(bucketId: String)

    @Query("SELECT COUNT(*) FROM synced_files")
    suspend fun getCount(): Int
}
