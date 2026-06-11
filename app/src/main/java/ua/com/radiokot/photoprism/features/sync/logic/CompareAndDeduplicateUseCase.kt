package ua.com.radiokot.photoprism.features.sync.logic

import ua.com.radiokot.photoprism.features.sync.data.storage.SyncedFileDao

class CompareAndDeduplicateUseCase(
    private val syncedFileDao: SyncedFileDao,
) {
    /**
     * Filters out already-synced files using a hybrid approach:
     * 1. First check by MediaStore._ID (fast)
     * 2. Fall back to filePath + dateModified (reliable)
     * @return only files that have NOT been synced before
     */
    suspend fun filterNewFiles(files: List<LocalMediaItem>): List<LocalMediaItem> {
        if (files.isEmpty()) return emptyList()

        val newFiles = mutableListOf<LocalMediaItem>()

        for (file in files) {
            val isSynced = if (file.mediaStoreId != null && file.mediaStoreId > 0) {
                // Step 1: Fast check by MediaStore ID
                syncedFileDao.countByMediaStoreId(file.mediaStoreId) > 0
            } else {
                false
            }

            if (!isSynced) {
                // Step 2: Fall back to path + date check
                val existsByPath = syncedFileDao.countByPathAndDate(
                    path = file.filePath,
                    date = file.dateModified,
                ) > 0

                if (!existsByPath) {
                    newFiles.add(file)
                }
            }
        }

        return newFiles
    }
}
