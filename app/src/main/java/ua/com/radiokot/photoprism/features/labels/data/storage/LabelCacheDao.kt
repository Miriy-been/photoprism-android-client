package ua.com.radiokot.photoprism.features.labels.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.com.radiokot.photoprism.features.labels.data.model.LabelCacheEntity

@Dao
interface LabelCacheDao {
    @Query("SELECT * FROM labels_cache ORDER BY name ASC")
    suspend fun getAll(): List<LabelCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LabelCacheEntity>)

    @Query("DELETE FROM labels_cache WHERE cachedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)

    @Query("DELETE FROM labels_cache")
    suspend fun deleteAll()
}
