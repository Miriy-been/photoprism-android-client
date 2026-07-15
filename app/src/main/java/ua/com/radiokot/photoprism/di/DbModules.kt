package ua.com.radiokot.photoprism.di

import android.content.ContentValues
import androidx.room.OnConflictStrategy
import androidx.room.Room
import org.koin.dsl.bind
import org.koin.dsl.module
import ua.com.radiokot.photoprism.db.AlbumCacheDao
import ua.com.radiokot.photoprism.db.AppDatabase
import ua.com.radiokot.photoprism.db.CachedMediaDao
import ua.com.radiokot.photoprism.db.roomMigration
import ua.com.radiokot.photoprism.features.labels.data.storage.LabelCacheDao
import ua.com.radiokot.photoprism.features.people.data.storage.PeopleCacheDao
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao

val appDbModule = module {
    single {
        Room
            .databaseBuilder(
                context = get(),
                klass = AppDatabase::class.java,
                name = "database"
            )
            .addMigrations(
                roomMigration(from = 1, to = 2) {
                    execSQL("ALTER TABLE `bookmarks` ADD COLUMN `include_private` INTEGER NOT NULL DEFAULT 0")
                },
                roomMigration(from = 2, to = 3) {
                    execSQL("ALTER TABLE `bookmarks` ADD COLUMN `album_uid` TEXT")
                },
                roomMigration(from = 3, to = 4) {
                    execSQL("CREATE TABLE IF NOT EXISTS `_new_bookmarks` (`id` INTEGER NOT NULL, `position` REAL NOT NULL, `name` TEXT NOT NULL, `user_query` TEXT, `media_types` TEXT, `include_private` INTEGER NOT NULL, `album_uid` TEXT, PRIMARY KEY(`id`))")
                    execSQL("INSERT INTO `_new_bookmarks` (`id`,`position`,`name`,`user_query`,`media_types`,`include_private`,`album_uid`) SELECT `id`,`position`,`name`,`user_query`,`media_types`,`include_private`,`album_uid` FROM `bookmarks`")
                    execSQL("DROP TABLE `bookmarks`")
                    execSQL("ALTER TABLE `_new_bookmarks` RENAME TO `bookmarks`")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_position` ON `bookmarks` (`position`)")

                    // Set [] media types to null.
                    val updateValues = ContentValues(1).apply {
                        putNull("media_types")
                    }
                    update(
                        "bookmarks",
                        OnConflictStrategy.ABORT,
                        updateValues,
                        "`media_types`=?",
                        arrayOf("[]")
                    )
                },
                roomMigration(from = 8, to = 9) {
                    // No schema changes; version bump to match on-disk DB
                },
                roomMigration(from = 11, to = 12) {
                    // No schema changes; version bump after cached_media was added in 9→10
                },
                roomMigration(from = 12, to = 13) {
                    execSQL("ALTER TABLE `cached_media` ADD COLUMN `localThumbnailPath` TEXT")
                },
                roomMigration(from = 13, to = 14) {
                    execSQL("ALTER TABLE `synced_files` ADD COLUMN `photoPrismHash` TEXT")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_synced_files_photoPrismHash` ON `synced_files` (`photoPrismHash`)")
                },
                roomMigration(from = 14, to = 15) {
                    execSQL("ALTER TABLE `sync_history` ADD COLUMN `folderName` TEXT")
                },
                roomMigration(from = 15, to = 16) {
                    execSQL("CREATE TABLE IF NOT EXISTS `albums_cache` (" +
                            "`uid` TEXT NOT NULL PRIMARY KEY, " +
                            "`type` TEXT NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`thumbnailHash` TEXT, " +
                            "`photoCount` INTEGER NOT NULL DEFAULT 0, " +
                            "`path` TEXT, " +
                            "`ymd` TEXT, " +
                            "`cachedAt` INTEGER NOT NULL" +
                            ")")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_albums_cache_type` ON `albums_cache` (`type`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_albums_cache_cachedAt` ON `albums_cache` (`cachedAt`)")
                },
                roomMigration(from = 16, to = 17) {
                    execSQL("CREATE TABLE IF NOT EXISTS `people_cache` (" +
                            "`id` TEXT NOT NULL PRIMARY KEY, " +
                            "`name` TEXT, " +
                            "`isFavorite` INTEGER NOT NULL DEFAULT 0, " +
                            "`photoCount` INTEGER NOT NULL DEFAULT 0, " +
                            "`thumbnailHash` TEXT, " +
                            "`cachedAt` INTEGER NOT NULL" +
                            ")")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_people_cache_cachedAt` ON `people_cache` (`cachedAt`)")
                    execSQL("CREATE TABLE IF NOT EXISTS `labels_cache` (" +
                            "`uid` TEXT NOT NULL PRIMARY KEY, " +
                            "`name` TEXT NOT NULL, " +
                            "`slug` TEXT NOT NULL, " +
                            "`isFavorite` INTEGER NOT NULL DEFAULT 0, " +
                            "`itemCount` INTEGER NOT NULL DEFAULT 0, " +
                            "`thumbnailHash` TEXT, " +
                            "`cachedAt` INTEGER NOT NULL" +
                            ")")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_labels_cache_cachedAt` ON `labels_cache` (`cachedAt`)")
                },
                roomMigration(from = 17, to = 18) {
                    execSQL("ALTER TABLE `cached_media` ADD COLUMN `albumUid` TEXT")
                },
                roomMigration(from = 18, to = 19) {
                    execSQL("CREATE TABLE IF NOT EXISTS `recycle_bin_items` (" +
                            "`photoUid` TEXT NOT NULL PRIMARY KEY, " +
                            "`archivedAt` INTEGER NOT NULL, " +
                            "`thumbnailHash` TEXT, " +
                            "`photoTitle` TEXT" +
                            ")")
                },
                roomMigration(from = 9, to = 10) {
                    execSQL("CREATE TABLE IF NOT EXISTS `cached_media` (" +
                            "`uid` TEXT NOT NULL PRIMARY KEY, " +
                            "`title` TEXT, " +
                            "`description` TEXT, " +
                            "`mediaType` TEXT NOT NULL, " +
                            "`mediaTypeRaw` TEXT, " +
                            "`takenAt` TEXT, " +
                            "`takenAtLocal` TEXT, " +
                            "`timeZone` TEXT, " +
                            "`favorite` INTEGER NOT NULL DEFAULT 0, " +
                            "`isPrivate` INTEGER NOT NULL DEFAULT 0, " +
                            "`lat` REAL, " +
                            "`lng` REAL, " +
                            "`altitude` REAL, " +
                            "`cameraModel` TEXT, " +
                            "`cameraMake` TEXT, " +
                            "`width` INTEGER, " +
                            "`height` INTEGER, " +
                            "`fileHash` TEXT, " +
                            "`cachedAt` INTEGER NOT NULL, " +
                            "`isReadOnly` INTEGER NOT NULL DEFAULT 0, " +
                            "`localThumbnailPath` TEXT, " +
                            "`albumUid` TEXT" +
                            ")")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_cached_media_takenAtLocal` ON `cached_media` (`takenAtLocal`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_cached_media_favorite` ON `cached_media` (`favorite`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_cached_media_cachedAt` ON `cached_media` (`cachedAt`)")
                },
            )
            .fallbackToDestructiveMigration()
            .build()
    } bind AppDatabase::class

    single {
        get<AppDatabase>().cachedMedia()
    } bind CachedMediaDao::class

    single {
        get<AppDatabase>().albumCache()
    } bind AlbumCacheDao::class

    single {
        get<AppDatabase>().peopleCache()
    } bind PeopleCacheDao::class

    single {
        get<AppDatabase>().labelCache()
    } bind LabelCacheDao::class

    single {
        get<AppDatabase>().recycleBin()
    } bind RecycleBinDao::class
}
