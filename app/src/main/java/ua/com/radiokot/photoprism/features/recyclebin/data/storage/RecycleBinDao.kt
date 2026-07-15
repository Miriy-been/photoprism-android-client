package ua.com.radiokot.photoprism.features.recyclebin.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable

@Dao
interface RecycleBinDao {
    @Query("SELECT * FROM recycle_bin_items ORDER BY archivedAt DESC")
    fun getAll(): Flowable<List<RecycleBinItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: RecycleBinItem)

    @Query("DELETE FROM recycle_bin_items WHERE photoUid IN (:uids)")
    fun deleteByUids(uids: Collection<String>)

    @Query("SELECT * FROM recycle_bin_items WHERE archivedAt < :before")
    suspend fun getExpiredItems(before: Long): List<RecycleBinItem>

    @Query("DELETE FROM recycle_bin_items WHERE archivedAt < :before")
    suspend fun deleteExpired(before: Long)

    @Query("SELECT COUNT(*) FROM recycle_bin_items")
    fun count(): Flowable<Int>
}