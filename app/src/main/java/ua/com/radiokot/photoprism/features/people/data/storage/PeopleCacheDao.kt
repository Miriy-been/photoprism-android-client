package ua.com.radiokot.photoprism.features.people.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.com.radiokot.photoprism.features.people.data.model.PeopleCacheEntity

@Dao
interface PeopleCacheDao {
    @Query("SELECT * FROM people_cache ORDER BY isFavorite DESC, photoCount DESC, name ASC")
    suspend fun getAll(): List<PeopleCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<PeopleCacheEntity>)

    @Query("DELETE FROM people_cache WHERE cachedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)

    @Query("DELETE FROM people_cache")
    suspend fun deleteAll()
}
