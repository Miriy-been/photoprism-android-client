package ua.com.radiokot.photoprism.di

import android.content.ContentValues
import androidx.room.OnConflictStrategy
import androidx.room.Room
import org.koin.dsl.bind
import org.koin.dsl.module
import ua.com.radiokot.photoprism.db.AppDatabase
import ua.com.radiokot.photoprism.db.CachedMediaDao
import ua.com.radiokot.photoprism.db.roomMigration

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
                            "`isReadOnly` INTEGER NOT NULL DEFAULT 0" +
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
}
