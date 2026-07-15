package ua.com.radiokot.photoprism.features.sync.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.com.radiokot.photoprism.features.sync.data.model.SyncedFile

@Dao
interface SyncedFileDao {

    @Query("SELECT COUNT(*) FROM synced_files WHERE mediaStoreId = :mediaStoreId AND mediaStoreId IS NOT NULL AND status = 'completed'")
    suspend fun countByMediaStoreId(mediaStoreId: Long): Int

    @Query("SELECT COUNT(*) FROM synced_files WHERE filePath = :path AND dateModified = :date AND status = 'completed'")
    suspend fun countByPathAndDate(path: String, date: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<SyncedFile>)

    @Query("DELETE FROM synced_files WHERE bucketId = :bucketId")
    suspend fun deleteByBucketId(bucketId: String)

    @Query("SELECT COUNT(*) FROM synced_files WHERE status = 'completed'")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM synced_files WHERE bucketId IN (:bucketIds) AND status = 'completed'")
    suspend fun getCountByBucketIds(bucketIds: List<String>): Int

    @Query("UPDATE synced_files SET status = 'completed' WHERE filePath IN (:filePaths)")
    suspend fun markCompleted(filePaths: List<String>)

    @Query("DELETE FROM synced_files WHERE status = 'pending' AND syncedAt < :threshold")
    suspend fun cleanupPending(threshold: Long)

    @Query("DELETE FROM synced_files WHERE filePath IN (:filePaths)")
    suspend fun deleteByFilePaths(filePaths: List<String>)

    /**
     * Returns completed [SyncedFile]s whose [SyncedFile.photoPrismHash] is null,
     * meaning they have been uploaded but not yet matched to a PhotoPrism hash.
     */
    @Query("SELECT * FROM synced_files WHERE status = 'completed' AND photoPrismHash IS NULL ORDER BY syncedAt DESC LIMIT :limit")
    suspend fun getCompletedWithNullHash(limit: Int = 100): List<SyncedFile>

    /**
     * Returns the first completed [SyncedFile] that matches the given PhotoPrism hash,
     * or null if none found.
     */
    @Query("SELECT * FROM synced_files WHERE photoPrismHash = :hash AND status = 'completed' LIMIT 1")
    suspend fun getByPhotoPrismHash(hash: String): SyncedFile?

    /**
     * Returns the local file path for a completed sync file matching the given PhotoPrism hash,
     * or null if not found.
     */
    @Query("SELECT filePath FROM synced_files WHERE photoPrismHash = :hash AND status = 'completed' LIMIT 1")
    suspend fun getFilePathByHash(hash: String): String?

    /**
     * Updates the PhotoPrism hash for a given synced file record.
     */
    @Query("UPDATE synced_files SET photoPrismHash = :hash WHERE id = :id")
    suspend fun updatePhotoPrismHash(id: Long, hash: String)

    /**
     * Updates the PhotoPrism hash for all synced files matching the given file paths.
     */
    @Query("UPDATE synced_files SET photoPrismHash = :hash WHERE filePath IN (:filePaths) AND photoPrismHash IS NULL")
    suspend fun updatePhotoPrismHashByPaths(filePaths: List<String>, hash: String)

    /**
     * Deletes all synced file records for the given bucket IDs,
     * resetting those folders' sync state. All local files will be
     * treated as "new" and re-uploaded on the next sync.
     */
    @Query("DELETE FROM synced_files WHERE bucketId IN (:bucketIds)")
    suspend fun deleteByBucketIds(bucketIds: List<String>)
}
