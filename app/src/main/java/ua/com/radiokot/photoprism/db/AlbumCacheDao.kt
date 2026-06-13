package ua.com.radiokot.photoprism.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AlbumCacheDao {
    @Query("SELECT * FROM albums_cache WHERE type = :type ORDER BY title ASC")
    suspend fun getByType(type: String): List<AlbumCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AlbumCacheEntity>)

    @Query("DELETE FROM albums_cache WHERE cachedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)

    @Query("DELETE FROM albums_cache WHERE type = :type")
    suspend fun deleteByType(type: String)
}
