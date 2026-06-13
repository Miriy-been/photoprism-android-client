package ua.com.radiokot.photoprism.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ua.com.radiokot.photoprism.features.ext.memories.data.model.MemoryDbEntity
import ua.com.radiokot.photoprism.features.ext.memories.data.storage.MemoriesDbDao
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchBookmarksDbEntity
import ua.com.radiokot.photoprism.features.gallery.data.storage.SearchBookmarksDbDao
import ua.com.radiokot.photoprism.features.labels.data.model.LabelCacheEntity
import ua.com.radiokot.photoprism.features.people.data.model.PeopleCacheEntity
import ua.com.radiokot.photoprism.features.people.data.storage.PeopleCacheDao
import ua.com.radiokot.photoprism.features.labels.data.storage.LabelCacheDao
import ua.com.radiokot.photoprism.features.sync.data.model.SyncHistoryItem
import ua.com.radiokot.photoprism.features.sync.data.model.SyncedFile
import ua.com.radiokot.photoprism.features.sync.data.model.SyncFolder
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncHistoryItemDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncFolderDao

@Database(
    version = 18,
    entities = [
        SearchBookmarksDbEntity::class,
        MemoryDbEntity::class,
        SyncedFile::class,
        SyncFolder::class,
        SyncHistoryItem::class,
        CachedMediaEntity::class,
        AlbumCacheEntity::class,
        PeopleCacheEntity::class,
        LabelCacheEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
    exportSchema = true,
)
@TypeConverters(
    value = [
        AppDatabaseConverters::class,
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarks(): SearchBookmarksDbDao
    abstract fun memories(): MemoriesDbDao
    abstract fun syncedFiles(): SyncedFileDao
    abstract fun syncFolders(): SyncFolderDao
    abstract fun syncHistory(): SyncHistoryItemDao
    abstract fun cachedMedia(): CachedMediaDao
    abstract fun albumCache(): AlbumCacheDao
    abstract fun peopleCache(): PeopleCacheDao
    abstract fun labelCache(): LabelCacheDao
}
