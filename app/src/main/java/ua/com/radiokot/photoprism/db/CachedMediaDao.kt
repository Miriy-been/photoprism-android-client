package ua.com.radiokot.photoprism.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CachedMediaDao {

    @Upsert
    fun upsert(entity: CachedMediaEntity)

    @Upsert
    fun upsertAll(entities: List<CachedMediaEntity>)

    @Query("SELECT * FROM cached_media WHERE uid = :uid")
    fun getByUid(uid: String): CachedMediaEntity?

    @Query("SELECT * FROM cached_media ORDER BY takenAtLocal DESC LIMIT :limit OFFSET :offset")
    fun getAllOrderedByDate(limit: Int, offset: Int): List<CachedMediaEntity>

    @Query("""
        SELECT * FROM cached_media 
        WHERE (:query IS NULL OR title LIKE '%' || :query || '%')
          AND (:mediaType IS NULL OR mediaType = :mediaType)
          AND (:before IS NULL OR takenAtLocal <= :before)
          AND (:after IS NULL OR takenAtLocal >= :after)
          AND (:albumUid IS NULL OR albumUid = :albumUid)
          AND (:onlyFavorite IS NULL OR favorite = :onlyFavorite)
          AND (:includePrivate IS NULL OR isPrivate = 0 OR :includePrivate = 1)
        ORDER BY takenAtLocal DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun getFilteredOrderedByDate(
        query: String?,
        mediaType: String?,
        before: String?,
        after: String?,
        albumUid: String?,
        onlyFavorite: Boolean?,
        includePrivate: Boolean?,
        limit: Int,
        offset: Int,
    ): List<CachedMediaEntity>

    @Query("SELECT * FROM cached_media WHERE favorite = 1 ORDER BY takenAtLocal DESC LIMIT :limit OFFSET :offset")
    fun getFavorites(limit: Int, offset: Int): List<CachedMediaEntity>

    @Query("SELECT COUNT(*) FROM cached_media")
    fun getCount(): Int

    @Query("UPDATE cached_media SET localThumbnailPath = :path WHERE uid = :uid")
    fun updateLocalThumbnailPath(uid: String, path: String?)

    @Query("DELETE FROM cached_media WHERE uid = :uid")
    fun deleteByUid(uid: String)

    @Query("DELETE FROM cached_media WHERE uid IN (SELECT uid FROM cached_media ORDER BY cachedAt ASC LIMIT :limit)")
    fun deleteOldest(limit: Int)

    @Query("DELETE FROM cached_media WHERE uid NOT IN (SELECT uid FROM cached_media ORDER BY cachedAt DESC LIMIT :keepCount)")
    fun deleteOldestExceptNewest(keepCount: Int)

    @Query("DELETE FROM cached_media")
    fun clearAll()
}
