package ua.com.radiokot.photoprism.features.sync.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ua.com.radiokot.photoprism.features.sync.data.model.SyncHistoryItem

@Dao
interface SyncHistoryItemDao {

    @Insert
    suspend fun insert(item: SyncHistoryItem)

    @Query("SELECT * FROM sync_history ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<SyncHistoryItem>

    @Query("SELECT COUNT(*) FROM sync_history")
    suspend fun getCount(): Int

    @Query("DELETE FROM sync_history WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
