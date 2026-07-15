# Recycle Bin (回收站) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "Archive" concept with a Recycle Bin. Gallery "Delete" moves items to Recycle Bin (archive API). Recycle Bin supports Restore and Permanent Delete. Auto-clear after 30 days (configurable, can be disabled).

**Architecture:** PhotoPrism API `batchArchive` / `batchRestore` / `batchDelete` endpoints manage photo lifecycle. Local Room table `recycle_bin_items` tracks when items were deleted for auto-clear timing. Recycle Bin UI is a standalone Activity similar to `GallerySingleRepositoryActivity`, showing archived items with custom actions. The "more" menu in the bottom nav and the "more" sheet in single-repo views gain a Recycle Bin entry.

**Tech Stack:** Kotlin, Room, RxJava3, PhotoPrism REST API, AndroidX Preference

## Global Constraints

- AppDatabase version 18 → 19, must add migration 18→19 (do not rely on fallbackToDestructiveMigration)
- All new strings go into `res/values/strings.xml` (English) and `res/values-b+zh+Hans/strings.xml` (Chinese Simplified)
- Preference keys go into `res/values/preference_keys.xml`
- Follow existing code patterns: `GallerySingleRepositoryActivity` → `RecycleBinActivity`, single-file ViewModel pattern
- Use existing drawable `ic_delete.xml` for recycle bin icon unless otherwise specified
- All new files under package `ua.com.radiokot.photoprism.features.recyclebin`

---
### Task 1: Database Layer — Entity, DAO, and Migration

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/data/storage/RecycleBinItem.kt`
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/data/storage/RecycleBinDao.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/db/AppDatabase.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/di/DbModules.kt`

**Interfaces:**
- Consumes: `AppDatabase` (Room database, version 18)
- Produces: `RecycleBinItem` entity, `RecycleBinDao` interface, `MIGRATION_18_19`

- [ ] **Step 1: Create RecycleBinItem entity**

Create `features/recyclebin/data/storage/RecycleBinItem.kt`:

```kotlin
package ua.com.radiokot.photoprism.features.recyclebin.data.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recycle_bin_items")
data class RecycleBinItem(
    @PrimaryKey
    val photoUid: String,
    val archivedAt: Long,
    val thumbnailHash: String?,
    val photoTitle: String?,
)
```

- [ ] **Step 2: Create RecycleBinDao**

Create `features/recyclebin/data/storage/RecycleBinDao.kt`:

```kotlin
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

    @Query("SELECT * FROM recycle_bin_items WHERE photoUid = :uid")
    suspend fun getByUid(uid: String): RecycleBinItem?
}
```

- [ ] **Step 3: Add entity to AppDatabase and update version to 19**

Modify `db/AppDatabase.kt`:

```kotlin
@Database(
    version = 19,  // was 18
    entities = [
        // ... existing entities
        RecycleBinItem::class,  // NEW
    ],
    autoMigrations = [/* ... existing ... */],
    // ...
)
abstract class AppDatabase : RoomDatabase() {
    // ... existing abstract funs
    abstract fun recycleBin(): RecycleBinDao  // NEW
}
```

Add import:
```kotlin
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinItem
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao
```

- [ ] **Step 4: Add migration 18→19**

Modify `di/DbModules.kt` — add the migration to `.addMigrations(...)`:

```kotlin
roomMigration(from = 18, to = 19) {
    execSQL("CREATE TABLE IF NOT EXISTS `recycle_bin_items` (" +
            "`photoUid` TEXT NOT NULL PRIMARY KEY, " +
            "`archivedAt` INTEGER NOT NULL, " +
            "`thumbnailHash` TEXT, " +
            "`photoTitle` TEXT" +
            ")")
},
```

Also add DAO binding after `labelCache` binding:

```kotlin
single {
    get<AppDatabase>().recycleBin()
} bind RecycleBinDao::class
```

Add import:
```kotlin
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao
```

- [ ] **Step 5: Build and verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(db): add recycle_bin_items table with entity, DAO, and migration 18→19"
```

---
### Task 2: API Layer — Add batchRestore and SearchConfig `archived` field

**Files:**
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/api/photos/service/PhotoPrismPhotosService.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/data/model/SearchConfig.kt`

**Interfaces:**
- Consumes: `PhotoPrismBatchPhotoUids` model (already exists)
- Produces: `batchRestore()` API method, `SearchConfig.archived` field

- [ ] **Step 1: Add batchRestore to PhotosService**

Modify `api/photos/service/PhotoPrismPhotosService.kt` — add after `batchArchive`:

```kotlin
@kotlin.jvm.Throws(IOException::class)
@Headers("Accept: application/json")
@POST("v1/batch/photos/restore")
fun batchRestore(
    @Body batchPhotoUids: PhotoPrismBatchPhotoUids,
): Any
```

- [ ] **Step 2: Add `archived` field to SearchConfig**

Modify `features/gallery/data/model/SearchConfig.kt`:

Add field to data class:
```kotlin
val archived: Boolean = false,
```

Modify `getPhotoPrismQuery()` — before the `return` statement, add:
```kotlin
if (archived) {
    queryBuilder.append(" archived:true")
}
```

Update `DEFAULT` companion:
```kotlin
val DEFAULT = SearchConfig(
    // ... existing values
    archived = false,
)
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(api): add batchRestore endpoint and SearchConfig.archived filter"
```

---
### Task 3: Add RestoreGalleryMediaUseCase and AutoClearRecycleBinUseCase

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/logic/RestoreGalleryMediaUseCase.kt`
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/logic/AutoClearRecycleBinUseCase.kt`

**Interfaces:**
- Consumes: `PhotoPrismPhotosService.batchRestore()`, `RecycleBinDao`, `SimpleGalleryMediaRepository`
- Produces: `RestoreGalleryMediaUseCase`, `AutoClearRecycleBinUseCase`

- [ ] **Step 1: Create RestoreGalleryMediaUseCase**

Create `features/recyclebin/logic/RestoreGalleryMediaUseCase.kt`:

```kotlin
package ua.com.radiokot.photoprism.features.recyclebin.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.photos.model.PhotoPrismBatchPhotoUids
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao

class RestoreGalleryMediaUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {
    private val log = kLogger("RestoreGalleryMediaUC")

    operator fun invoke(mediaUids: Collection<String>): Completable =
        Completable.defer {
            photoPrismPhotosService.batchRestore(
                PhotoPrismBatchPhotoUids(mediaUids)
            )
            Completable.complete()
        }
            .subscribeOn(Schedulers.io())
            .doOnComplete {
                recycleBinDao.deleteByUids(mediaUids)
                log.debug {
                    "invoke(): restored_and_removed_from_local:" +
                            "\nitems=${mediaUids.size}"
                }
            }
}
```

- [ ] **Step 2: Create AutoClearRecycleBinUseCase**

Create `features/recyclebin/logic/AutoClearRecycleBinUseCase.kt`:

```kotlin
package ua.com.radiokot.photoprism.features.recyclebin.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.photos.model.PhotoPrismBatchPhotoUids
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao

class AutoClearRecycleBinUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {
    private val log = kLogger("AutoClearRecycleBinUC")

    /**
     * @param retentionDays items older than this many days will be permanently deleted.
     * Pass null or 0 to skip clearing (disabled).
     */
    operator fun invoke(retentionDays: Int?): Completable {
        if (retentionDays == null || retentionDays <= 0) {
            log.debug { "invoke(): auto_clear_disabled_skipping" }
            return Completable.complete()
        }

        return Completable.defer {
            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
            val expiredItems = recycleBinDao.getExpiredItems(cutoff)
            
            if (expiredItems.isEmpty()) {
                log.debug { "invoke(): no_expired_items_found" }
                return@defer Completable.complete()
            }

            val expiredUids = expiredItems.map { it.photoUid }
            log.debug {
                "invoke(): clearing_expired_items:" +
                        "\ncount=${expiredUids.size}," +
                        "\nretentionDays=$retentionDays"
            }

            photoPrismPhotosService.batchDelete(
                PhotoPrismBatchPhotoUids(expiredUids)
            )
            recycleBinDao.deleteExpired(cutoff)
            Completable.complete()
        }
            .subscribeOn(Schedulers.io())
            .doOnComplete {
                log.debug { "invoke(): auto_clear_completed" }
            }
    }
}
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(recyclebin): add RestoreGalleryMediaUseCase and AutoClearRecycleBinUseCase"
```

---
### Task 4: Modify Delete Flow — Archive Instead of Permanent Delete

**Files:**
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/logic/DeleteGalleryMediaUseCase.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/view/model/GalleryMediaRemoteActionsViewModelDelegateImpl.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/view/GalleryActivity.kt` (dialog text)
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/viewer/view/MediaViewerActivity.kt` (dialog text)

**Interfaces:**
- Consumes: `PhotoPrismPhotosService.batchArchive()`, `RecycleBinDao`, `SimpleGalleryMediaRepository`, `MediaPreviewUrlFactory`
- Produces: Modified delete flow that archives instead of permanently deleting

- [ ] **Step 1: Modify DeleteGalleryMediaUseCase to archive instead of delete**

Modify `features/gallery/logic/DeleteGalleryMediaUseCase.kt`:

```kotlin
package ua.com.radiokot.photoprism.features.gallery.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.photos.model.PhotoPrismBatchPhotoUids
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinItem

class DeleteGalleryMediaUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {
    private val log = kLogger("DeleteGalleryMediaUC")

    /**
     * Moves items to the Recycle Bin (archives them on the server
     * and records the deletion time locally).
     */
    operator fun invoke(
        mediaUids: Collection<String>,
        currentGalleryMediaRepository: SimpleGalleryMediaRepository,
    ): Completable =
        currentGalleryMediaRepository
            .archive(itemUids = mediaUids)
            .andThen(
                Completable.defer {
                    val now = System.currentTimeMillis()
                    val items = currentGalleryMediaRepository.itemsList
                    mediaUids.forEach { uid ->
                        val media = items.find { it.uid == uid }
                        recycleBinDao.insert(
                            RecycleBinItem(
                                photoUid = uid,
                                archivedAt = now,
                                thumbnailHash = media?.hash,
                                photoTitle = media?.title,
                            )
                        )
                    }
                    log.debug {
                        "invoke(): recorded_in_recycle_bin:" +
                                "\nitems=${mediaUids.size}"
                    }
                    Completable.complete()
                }
                    .subscribeOn(Schedulers.io())
            )

    operator fun invoke(
        mediaUid: String,
        currentGalleryMediaRepository: SimpleGalleryMediaRepository,
    ) = invoke(
        setOf(mediaUid),
        currentGalleryMediaRepository,
    )
}
```

- [ ] **Step 2: Update GalleryMediaRemoteActionsViewModelDelegateImpl — change delete dialog text**

Modify `features/gallery/view/model/GalleryMediaRemoteActionsViewModelDelegateImpl.kt` — the `deleteGalleryMedia` method sends `Event.OpenDeletingConfirmationDialog`. The dialog text is rendered in the Activity, so we need a new event type or just change what the dialog says.

Actually, we keep the same event `OpenDeletingConfirmationDialog`. We'll change the dialog text in the Activity. No changes needed in the delegate.

But we need to change the import — remove `DeleteGalleryMediaUseCase` from constructor and replace with the new one. Let me update:

Modify the constructor:
```kotlin
class GalleryMediaRemoteActionsViewModelDelegateImpl(
    private val archiveGalleryMediaUseCase: ArchiveGalleryMediaUseCase,
    private val deleteGalleryMediaUseCase: DeleteGalleryMediaUseCase,  // now archives
    // ... rest unchanged
)
```

No code changes needed in this file — the `deleteGalleryMediaUseCase` is already injected and called. The use case now archives instead of deletes.

- [ ] **Step 3: Change delete confirmation dialog text in GalleryActivity**

In `GalleryActivity.kt`, modify `openDeletingConfirmationDialog()`:

```kotlin
private fun openDeletingConfirmationDialog() {
    val selectedCount = viewModel.selectedItemsCount.value ?: 0
    val message = if (selectedCount > 1) {
        getString(R.string.gallery_delete_to_recycle_bin_plural, selectedCount)
    } else {
        getString(R.string.gallery_delete_to_recycle_bin_single)
    }
    val retentionDays = 30  // Default, can be read from preferences later

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.recycle_bin_title)
        .setMessage("$message\n\n${getString(R.string.recycle_bin_auto_clear_hint, retentionDays)}")
        .setPositiveButton(R.string.move_to_recycle_bin) { _, _ ->
            viewModel.onDeletingGalleryMediaConfirmed()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
```

- [ ] **Step 4: Change delete confirmation dialog text in MediaViewerActivity**

In `MediaViewerActivity.kt`, modify `openDeletingConfirmationDialog()`:

```kotlin
private fun openDeletingConfirmationDialog() {
    val retentionDays = 30

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.recycle_bin_title)
        .setMessage(getString(R.string.media_viewer_delete_to_recycle_bin, retentionDays))
        .setPositiveButton(R.string.move_to_recycle_bin) { _, _ ->
            viewModel.onDeletingGalleryMediaConfirmed()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
```

- [ ] **Step 5: Build and verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(gallery): change delete flow to move items to recycle bin (archive)"
```

---
### Task 5: Add RecycleBinViewModel

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/view/model/RecycleBinViewModel.kt`

**Interfaces:**
- Consumes: `SimpleGalleryMediaRepository.Factory`, `GalleryPreferences`, `RestoreGalleryMediaUseCase`, `DeleteGalleryMediaUseCase` (permanent delete), `RecycleBinDao`, `AutoClearRecycleBinUseCase`, `RecycleBinPreferences`
- Produces: `RecycleBinViewModel` — state, events, actions

- [ ] **Step 1: Create RecycleBinViewModel**

Create `features/recyclebin/view/model/RecycleBinViewModel.kt`:

```kotlin
package ua.com.radiokot.photoprism.features.recyclebin.view.model

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.GalleryPreferences
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryContentLoadingError
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryListItem
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryListViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryListViewModelImpl
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaDownloadActionsViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaDownloadActionsViewModelDelegate
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModelDelegate
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinDao
import ua.com.radiokot.photoprism.features.recyclebin.logic.AutoClearRecycleBinUseCase
import ua.com.radiokot.photoprism.features.recyclebin.logic.RestoreGalleryMediaUseCase
import ua.com.radiokot.photoprism.features.gallery.logic.DeleteGalleryMediaUseCase
import ua.com.radiokot.photoprism.features.recyclebin.data.storage.RecycleBinPreferences

class RecycleBinViewModel(
    private val galleryMediaRepositoryFactory: SimpleGalleryMediaRepository.Factory,
    private val galleryPreferences: GalleryPreferences,
    private val restoreGalleryMediaUseCase: RestoreGalleryMediaUseCase,
    private val deleteGalleryMediaUseCase: DeleteGalleryMediaUseCase,
    private val recycleBinDao: RecycleBinDao,
    private val autoClearRecycleBinUseCase: AutoClearRecycleBinUseCase,
    private val recycleBinPreferences: RecycleBinPreferences,
    private val listViewModel: GalleryListViewModelImpl,
    private val galleryMediaDownloadActionsViewModel: GalleryMediaDownloadActionsViewModelDelegate,
    private val galleryMediaRemoteActionsViewModel: GalleryMediaRemoteActionsViewModelDelegate,
) : ViewModel(),
    GalleryListViewModel by listViewModel,
    GalleryMediaDownloadActionsViewModel by galleryMediaDownloadActionsViewModel,
    GalleryMediaRemoteActionsViewModel by galleryMediaRemoteActionsViewModel {

    private val log = kLogger("RecycleBinVM")
    private var isInitialized = false

    private val archivedSearchConfig = SearchConfig.DEFAULT.copy(archived = true)
    private val archivedRepositoryParams = SimpleGalleryMediaRepository.Params(
        searchConfig = archivedSearchConfig,
    )
    val mediaRepository: SimpleGalleryMediaRepository
        get() = galleryMediaRepositoryFactory.get(archivedRepositoryParams)

    val isLoading: MutableLiveData<Boolean> = MutableLiveData(false)
    val mainError = MutableLiveData<Error?>(null)
    private val eventsSubject = PublishSubject.create<Event>()
    val events: Observable<Event> = eventsSubject.observeOnMain()

    // Recycle bin item count from local DB
    val localItemCount: MutableLiveData<Int> = MutableLiveData(0)

    fun initOnce() {
        if (isInitialized) {
            return
        }

        // Run auto-clear on init
        runAutoClear()

        // Count local items
        recycleBinDao.getAll()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { items ->
                localItemCount.value = items.size
            }
            .autoDispose(this)

        initRepository()
        isInitialized = true
    }

    private fun runAutoClear() {
        val isEnabled = recycleBinPreferences.autoClearEnabled.value ?: true
        val retentionDays = if (isEnabled) {
            recycleBinPreferences.autoClearDays.value ?: 30
        } else {
            null
        }

        autoClearRecycleBinUseCase(retentionDays)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onError = { error ->
                    log.error(error) { "runAutoClear(): failed" }
                }
            )
            .autoDispose(this)
    }

    private fun initRepository() {
        // Subscribe to repository items similar to GallerySingleRepositoryViewModel
        // but using the archived search config
        
        // For simplicity — can reuse the same pattern as the gallery VM.
        // The actual list initialization will be in the Activity.
    }

    fun onRestoreClicked(mediaUids: Collection<String>) {
        restoreGalleryMediaUseCase(mediaUids)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onComplete = {
                    eventsSubject.onNext(Event.ItemsRestored(mediaUids.size))
                    mediaRepository.invalidate()
                    mediaRepository.update()
                },
                onError = { error ->
                    log.error(error) { "onRestoreClicked(): failed" }
                    eventsSubject.onNext(Event.ShowError(error.message ?: "Restore failed"))
                }
            )
            .autoDispose(this)
    }

    fun onPermanentDeleteClicked(mediaUids: Collection<String>) {
        Completable.defer {
            photoPrismPhotosService.batchDelete(
                PhotoPrismBatchPhotoUids(mediaUids)
            )
            Completable.complete()
        }
            .subscribeOn(Schedulers.io())
            .doOnComplete {
                recycleBinDao.deleteByUids(mediaUids)
                mediaRepository.removeLocally(mediaUids.toSet())
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onComplete = {
                    eventsSubject.onNext(Event.ItemsPermanentlyDeleted(mediaUids.size))
                },
                onError = { error ->
                    log.error(error) { "onPermanentDeleteClicked(): failed" }
                    eventsSubject.onNext(Event.ShowError(error.message ?: "Delete failed"))
                }
            )
            .autoDispose(this)
    }

    fun onEmptyRecycleBinClicked() {
        // Get all items from repository and delete them permanently
        val allUids = mediaRepository.itemsList.map { it.uid }
        if (allUids.isEmpty()) return

        onPermanentDeleteClicked(allUids)
    }

    sealed interface Event {
        class ItemsRestored(val count: Int) : Event
        class ItemsPermanentlyDeleted(val count: Int) : Event
        class ShowError(val message: String) : Event
    }

    sealed interface Error {
        object NoMediaFound : Error
        class ContentLoadingError(val error: GalleryContentLoadingError) : Error
    }
}
```

Wait, this is getting complex. Let me simplify the ViewModel by following the existing pattern more closely. The `GallerySingleRepositoryViewModel` is a much closer template. The recycle bin needs to show the same grid, but with different bottom bar actions.

Actually, for the recycle bin, the simplest approach is to:
1. Use `GallerySingleRepositoryViewModel` as-is for the grid display
2. Override the bottom bar actions to show "Restore" and "Permanent Delete" instead of "Archive" and "Delete"
3. Handle the restore/permanent-delete logic

But `GallerySingleRepositoryViewModel` is a concrete class, not an interface... Let me just create a simpler ViewModel that reuses the gallery infrastructure.

Let me think about this more carefully. The simplest approach:

The RecycleBinActivity can reuse `GallerySingleRepositoryViewModel` for the grid display (same grid, same search, same viewing/selection). The bottom bar menu will be different — we'll have a separate menu XML for it. The view model needs custom handlers for restore and permanent delete.

Let me write a cleaner version.

Actually, the cleanest approach is to make `RecycleBinActivity` similar to `GallerySingleRepositoryActivity` but with a different ViewModel. It uses the same `SimpleGalleryMediaRepository` with `archived = true` SearchConfig, and same list infrastructure, but the bottom bar has "Restore" and "Permanent Delete" buttons.

Let me simplify — just put enough logic in the ViewModel to work, and handle the UI details in the Activity.

```kotlin
class RecycleBinViewModel(
    private val galleryMediaRepositoryFactory: SimpleGalleryMediaRepository.Factory,
    private val galleryMediaRemoteActionsViewModel: GalleryMediaRemoteActionsViewModelDelegate,
    private val listViewModel: GalleryListViewModelImpl,
    private val recycleBinDao: RecycleBinDao,
    private val autoClearRecycleBinUseCase: AutoClearRecycleBinUseCase,
    private val recycleBinPreferences: RecycleBinPreferences,
) : ViewModel() {
    // Uses archived search config
    // Provides: restore action, permanent delete action, empty all action
    // Delegates to listViewModel for gallery grid
}
```

Let me finalize the plan and write it. The actual ViewModel code will be written during implementation, I just need to outline the structure now.</think>

<｜DSML｜tool_calls>
<｜DSML｜invoke name="Write">
<｜DSML｜parameter name="content" string="true"># Recycle Bin (回收站) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "Archive" concept with a Recycle Bin. Gallery "Delete" moves items to Recycle Bin (archive API). Recycle Bin supports Restore and Permanent Delete. Auto-clear after 30 days (configurable, can be disabled).

**Architecture:** PhotoPrism API `batchArchive` / `batchRestore` / `batchDelete` endpoints manage photo lifecycle. Local Room table `recycle_bin_items` tracks when items were deleted for auto-clear timing. Recycle Bin UI is a standalone Activity similar to `GallerySingleRepositoryActivity`, showing archived items with custom restore/permanent-delete actions. The "more" menu in bottom nav and `GallerySingleRepositoryFragment`'s more sheet gain a Recycle Bin entry.

**Tech Stack:** Kotlin, Room, RxJava3, PhotoPrism REST API, AndroidX Preference

## Global Constraints

- AppDatabase version 18 → 19, add migration 18→19 (do NOT rely on fallbackToDestructiveMigration)
- New strings go into `res/values/strings.xml` (English) and `res/values-b+zh+Hans/strings.xml` (Chinese Simplified)
- Preference keys go into `res/values/preference_keys.xml`
- Follow existing code patterns: `GallerySingleRepositoryActivity` as template
- Use existing drawable `ic_delete.xml` for recycle bin icon
- All new files under package `ua.com.radiokot.photoprism.features.recyclebin`

---
### Task 1: Database Layer — Entity, DAO, and Migration 18→19

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/data/storage/RecycleBinItem.kt`
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/data/storage/RecycleBinDao.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/db/AppDatabase.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/di/DbModules.kt`

**Interfaces:**
- Consumes: `AppDatabase` (Room, version 18)
- Produces: `RecycleBinItem` entity, `RecycleBinDao`, `RecycleBinDao` Koin binding, MIGRATION 18→19

- [ ] **Step 1: Create entity** `features/recyclebin/data/storage/RecycleBinItem.kt`

```kotlin
package ua.com.radiokot.photoprism.features.recyclebin.data.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recycle_bin_items")
data class RecycleBinItem(
    @PrimaryKey
    val photoUid: String,
    val archivedAt: Long,
    val thumbnailHash: String?,
    val photoTitle: String?,
)
```

- [ ] **Step 2: Create DAO** `features/recyclebin/data/storage/RecycleBinDao.kt`

```kotlin
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
```

- [ ] **Step 3: Register entity in AppDatabase** (`db/AppDatabase.kt`)
  - Bump version to 19
  - Add `RecycleBinItem::class` to entities list
  - Add `abstract fun recycleBin(): RecycleBinDao`
  - Add imports

- [ ] **Step 4: Add migration 18→19** in `di/DbModules.kt`:

```kotlin
roomMigration(from = 18, to = 19) {
    execSQL("CREATE TABLE IF NOT EXISTS `recycle_bin_items` (" +
            "`photoUid` TEXT NOT NULL PRIMARY KEY, " +
            "`archivedAt` INTEGER NOT NULL, " +
            "`thumbnailHash` TEXT, " +
            "`photoTitle` TEXT" +
            ")")
},
```

- [ ] **Step 5: Add DAO binding** in `di/DbModules.kt`:

```kotlin
single {
    get<AppDatabase>().recycleBin()
} bind RecycleBinDao::class
```

- [ ] **Step 6: Build and commit**

---
### Task 2: API + SearchConfig Changes

**Files:**
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/api/photos/service/PhotoPrismPhotosService.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/data/model/SearchConfig.kt`

**Interfaces:**
- Consumes: `PhotoPrismBatchPhotoUids`
- Produces: `PhotoPrismPhotosService.batchRestore()`, `SearchConfig.archived` field

- [ ] **Step 1: Add batchRestore method** to `PhotoPrismPhotosService`:

```kotlin
@kotlin.jvm.Throws(IOException::class)
@Headers("Accept: application/json")
@POST("v1/batch/photos/restore")
fun batchRestore(
    @Body batchPhotoUids: PhotoPrismBatchPhotoUids,
): Any
```

- [ ] **Step 2: Add `archived` field to `SearchConfig`** (`features/gallery/data/model/SearchConfig.kt`):
  - Add to data class: `val archived: Boolean = false,`
  - In `getPhotoPrismQuery()`, before return: `if (archived) queryBuilder.append(" archived:true")`
  - Update `DEFAULT` companion object: add `archived = false`

- [ ] **Step 3: Build and commit**

---
### Task 3: Use Cases — Restore and AutoClear

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/logic/RestoreGalleryMediaUseCase.kt`
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/logic/AutoClearRecycleBinUseCase.kt`

- [ ] **Step 1: Create `RestoreGalleryMediaUseCase`** — calls `batchRestore`, then deletes local record:

```kotlin
class RestoreGalleryMediaUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {
    operator fun invoke(mediaUids: Collection<String>): Completable =
        Completable.defer {
            photoPrismPhotosService.batchRestore(PhotoPrismBatchPhotoUids(mediaUids))
            Completable.complete()
        }
            .subscribeOn(Schedulers.io())
            .doOnComplete {
                recycleBinDao.deleteByUids(mediaUids)
            }
}
```

- [ ] **Step 2: Create `AutoClearRecycleBinUseCase`** — queries expired items, batchDeletes them:

```kotlin
class AutoClearRecycleBinUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {
    operator fun invoke(retentionDays: Int?): Completable {
        if (retentionDays == null || retentionDays <= 0) return Completable.complete()
        return Completable.defer {
            val cutoff = System.currentTimeMillis() - (retentionDays * 86400000L)
            val expired = recycleBinDao.getExpiredItems(cutoff)
            if (expired.isEmpty()) return@defer Completable.complete()
            photoPrismPhotosService.batchDelete(PhotoPrismBatchPhotoUids(expired.map { it.photoUid }))
            recycleBinDao.deleteExpired(cutoff)
            Completable.complete()
        }.subscribeOn(Schedulers.io())
    }
}
```

- [ ] **Step 3: Build and commit**

---
### Task 4: Add RecycleBinPreferences

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/data/storage/RecycleBinPreferences.kt`

This stores auto-clear enabled state and retention days using `BehaviorSubject` + `SharedPreferences`.

```kotlin
package ua.com.radiokot.photoprism.features.recyclebin.data.storage

import android.content.SharedPreferences
import io.reactivex.rxjava3.subjects.BehaviorSubject

interface RecycleBinPreferences {
    val autoClearEnabled: BehaviorSubject<Boolean>
    val autoClearDays: BehaviorSubject<Int>
}

class RecycleBinPreferencesOnPrefs(
    private val preferences: SharedPreferences,
) : RecycleBinPreferences {
    override val autoClearEnabled: BehaviorSubject<Boolean> = ...
    override val autoClearDays: BehaviorSubject<Int> = ...
}
```

- [ ] **Step 1: Create interface and implementation** following the pattern of `GalleryNavPreferences` / `GalleryNavPreferencesOnPrefs` (use `stringifyPreferenceSubject` helper).

- [ ] **Step 2: Add Koin binding** in `GalleryFeatureModule.kt` or a new recycle bin module:
  - Scope: singleton (not session-scoped — uses APP_NO_BACKUP_PREFERENCES)

- [ ] **Step 3: Build and commit**

---
### Task 5: Modify DeleteGalleryMediaUseCase — Archive + Record Locally

**Files:**
- Rewrite: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/logic/DeleteGalleryMediaUseCase.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/GalleryFeatureModule.kt`

- [ ] **Step 1: Rewrite `DeleteGalleryMediaUseCase`** to:
  - Call `currentGalleryMediaRepository.archive()` (not `delete()`)
  - Insert a `RecycleBinItem` record with current timestamp
  - Remove from local repository items

```kotlin
class DeleteGalleryMediaUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
) {
    operator fun invoke(
        mediaUids: Collection<String>,
        currentGalleryMediaRepository: SimpleGalleryMediaRepository,
    ): Completable =
        currentGalleryMediaRepository.archive(itemUids = mediaUids)
            .andThen(
                Completable.defer {
                    val now = System.currentTimeMillis()
                    val items = currentGalleryMediaRepository.itemsList
                    mediaUids.forEach { uid ->
                        val media = items.find { it.uid == uid }
                        recycleBinDao.insert(
                            RecycleBinItem(
                                photoUid = uid,
                                archivedAt = now,
                                thumbnailHash = media?.hash,
                                photoTitle = media?.title,
                            )
                        )
                    }
                    Completable.complete()
                }.subscribeOn(Schedulers.io())
            )
}
```

- [ ] **Step 2: Update DI** — `DeleteGalleryMediaUseCase` now needs `PhotoPrismPhotosService` and `RecycleBinDao`. The `GalleryFeatureModule.kt` already wires it via `scopedOf(::DeleteGalleryMediaUseCase)` which uses constructor injection — add the new deps to the constructor and Koin will inject them.

- [ ] **Step 3: Remove element of surprise** — In UI, change the confirmation dialog text. The "Delete" button now shows "Move to Recycle Bin" text. This is done in Task 9.

- [ ] **Step 4: Build and commit**

---
### Task 6: RecycleBinViewModel

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/view/model/RecycleBinViewModel.kt`

**Design:** Follows `GallerySingleRepositoryViewModel` pattern. Uses `SimpleGalleryMediaRepository` with `SearchConfig(archived = true)`. Provides `restoreSelected()`, `permanentlyDeleteSelected()`, `emptyAll()` methods. Delegates list display to `GalleryListViewModelImpl`.

- [ ] **Step 1: Create RecycleBinViewModel**

```kotlin
class RecycleBinViewModel(
    private val galleryMediaRepositoryFactory: SimpleGalleryMediaRepository.Factory,
    private val restoreGalleryMediaUseCase: RestoreGalleryMediaUseCase,
    private val deleteGalleryMediaUseCase: DeleteGalleryMediaUseCase,  // archive (move to bin)
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val recycleBinDao: RecycleBinDao,
    private val autoClearRecycleBinUseCase: AutoClearRecycleBinUseCase,
    private val recycleBinPreferences: RecycleBinPreferences,
    private val galleryPreferences: GalleryPreferences,
    private val listViewModel: GalleryListViewModelImpl,
    private val galleryMediaDownloadActionsVM: GalleryMediaDownloadActionsViewModelDelegate,
    private val galleryMediaRemoteActionsVM: GalleryMediaRemoteActionsViewModelDelegate,
) : ViewModel(),
    GalleryListViewModel by listViewModel,
    GalleryMediaDownloadActionsViewModel by galleryMediaDownloadActionsVM,
    GalleryMediaRemoteActionsViewModel by galleryMediaRemoteActionsVM {

    // - Holds a repository with archived search config
    // - Runs autoClear on init
    // - Provides: onRestoreMultipleSelectionClicked(), onPermanentDeleteMultipleSelectionClicked(), onEmptyRecycleBinClicked()
    // - Events: ItemsRestored(count), ItemsDeleted(count), ShowError(msg), Finish
}
```

The ViewModel should be minimal — most grid logic is in `GalleryListViewModelImpl`/`GalleryListViewModel`. The recycle bin just provides 3 custom actions on top.

- [ ] **Step 2: Build and commit**

---
### Task 7: RecycleBinActivity — Full UI

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/view/RecycleBinActivity.kt`
- Create: `app/src/main/res/menu/recycle_bin_selecting.xml`
- Create: `app/src/main/res/layout/activity_recycle_bin.xml` (or reuse `activity_gallery_single_repository.xml`)
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/di/RecycleBinFeatureModule.kt` (new)

**Design:** Similar to `GallerySingleRepositoryActivity` but:
- Bottom bar has: Restore (ic_restore) | Permanently Delete (ic_delete)
- Top toolbar has: title "Recycle Bin", menu with "Empty Recycle Bin" action
- Empty state: "Recycle Bin is empty"
- On init: runs auto-clear check

- [ ] **Step 1: Create layout** (or reuse `activity_gallery_single_repository` — same layout works)

- [ ] **Step 2: Create menu XML** `res/menu/recycle_bin_selecting.xml`:

```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/restore"
        android:icon="@drawable/ic_restore"  // need new icon
        android:title="Restore"
        app:iconTint="?colorOnSurfaceVariant"
        app:showAsAction="always" />
    <item
        android:id="@+id/permanent_delete"
        android:icon="@drawable/ic_delete"
        android:title="Delete permanently"
        app:iconTint="?colorOnSurfaceVariant"
        app:showAsAction="always" />
    <item
        android:id="@+id/add_to_favorites"
        android:icon="@drawable/ic_favorite"
        android:title="Add to favorites"
        app:iconTint="?colorOnSurfaceVariant"
        app:showAsAction="never" />
    <item
        android:id="@+id/remove_from_favorites"
        android:icon="@drawable/ic_favorite"
        android:title="Remove from favorites"
        app:iconTint="?colorOnSurfaceVariant"
        app:showAsAction="never" />
</menu>
```

- [ ] **Step 3: Create RecycleBinActivity** — template based on `GallerySingleRepositoryActivity`:
  - Reads repo with `SearchConfig.DEFAULT.copy(archived = true)`
  - Bottom bar uses `recycle_bin_selecting.xml`
  - `onRestoreClicked`, `onPermanentDeleteClicked`, `onEmptyRecycleBinClicked`
  - Permanent delete → confirmation dialog → `batchDelete` + remove local record
  - Restore → `batchRestore` + remove local record + invalidate repository
  - Empty all → confirmation → iterate all items

- [ ] **Step 4: Create DI module** — bind ViewModel and use cases for recycle bin scope.

- [ ] **Step 5: Register activity** in `AndroidManifest.xml`

- [ ] **Step 6: Add `ic_restore` drawable** — copy from Material icons or use a simple vector.

- [ ] **Step 7: Build and commit**

---
### Task 8: Navigation — Add Recycle Bin to "More" Menu

**Files:**
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/view/GalleryNavigationView.kt`
- Modify: `app/src/main/res/layout/dialog_more_menu.xml`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/view/GallerySingleRepositoryFragment.kt` (showMoreSheet)
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/view/GallerySingleRepositoryActivity.kt` (showMoreSheet)

- [ ] **Step 1: Add recycle bin entry to `dialog_more_menu.xml`** — add after sync settings, before the divider:

```xml
<!-- 回收站 -->
<LinearLayout
    android:id="@+id/more_recycle_bin"
    style="@style/WabiMoreMenuItem"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">

    <androidx.appcompat.widget.AppCompatImageView
        style="@style/WabiMoreMenuIcon"
        android:src="@drawable/ic_delete" />

    <TextView
        style="@style/WabiMoreMenuText"
        android:text="@string/recycle_bin_title" />
</LinearLayout>
```

- [ ] **Step 2: Update `GalleryNavigationView.showMoreMenu()`** — add click handler for `moreRecycleBin`:

```kotlin
binding.moreRecycleBin.setOnClickListener {
    closeAnd {
        anchorView.context.startActivity(
            Intent(anchorView.context, RecycleBinActivity::class.java)
        )
    }
}
```

- [ ] **Step 3: Update `GallerySingleRepositoryFragment.showMoreSheet()`** — add "Recycle Bin" option.

- [ ] **Step 4: Update `GallerySingleRepositoryActivity.showMoreSheet()`** — same.

- [ ] **Step 5: Build and commit**

---
### Task 9: Update Dialog Texts and Remove Archive Action

**Files:**
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/view/GalleryActivity.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/viewer/view/MediaViewerActivity.kt`
- Modify: `app/src/main/res/menu/gallery_selecting.xml`
- Modify: `app/src/main/res/menu/media_viewer.xml`

- [ ] **Step 1: Change GalleryActivity delete dialog** — replace message and button text:

Before: `R.string.gallery_deleting_confirmation` ("Delete selected items forever?")
After: New string with "Move to Recycle Bin" and auto-clear hint.

- [ ] **Step 2: Change MediaViewerActivity delete dialog** — same as above.

- [ ] **Step 3: Remove "Archive" from `gallery_selecting.xml`** — the "Archive" menu item was the old concept. Now the "Delete" button archives. Remove the archive item from the menu XML entirely.

- [ ] **Step 4: Remove "Archive" from `media_viewer.xml`** — same.

- [ ] **Step 5: Build and commit**

---
### Task 10: Settings — Recycle Bin Preferences

**Files:**
- Modify: `app/src/main/res/xml/preferences.xml`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/prefs/view/PreferencesFragment.kt`
- Modify: `app/src/main/res/values/preference_keys.xml`

- [ ] **Step 1: Add preference keys** in `preference_keys.xml`:

```xml
<string name="pk_recycle_bin_auto_clear" translatable="false">recycle_bin_auto_clear</string>
<string name="pk_recycle_bin_auto_clear_days" translatable="false">recycle_bin_auto_clear_days</string>
```

- [ ] **Step 2: Add Recycle Bin category** in `preferences.xml`:

```xml
<PreferenceCategory
    app:iconSpaceReserved="false"
    app:title="@string/recycle_bin_title">

    <SwitchPreferenceCompat
        app:iconSpaceReserved="false"
        app:key="@string/pk_recycle_bin_auto_clear"
        app:persistent="false"
        app:summary="@string/recycle_bin_auto_clear_summary"
        app:title="@string/recycle_bin_auto_clear_title"
        app:widgetLayout="@layout/preference_material_switch" />

    <ListPreference
        app:dependency="@string/pk_recycle_bin_auto_clear"
        app:iconSpaceReserved="false"
        app:key="@string/pk_recycle_bin_auto_clear_days"
        app:entries="@array/recycle_bin_auto_clear_days_entries"
        app:entryValues="@array/recycle_bin_auto_clear_days_values"
        app:persistent="false"
        app:title="@string/recycle_bin_auto_clear_days_title" />

</PreferenceCategory>
```

- [ ] **Step 3: Add array resources** in `res/values/arrays.xml`:

```xml
<string-array name="recycle_bin_auto_clear_days_entries">
    <item>7 days</item>
    <item>14 days</item>
    <item>30 days</item>
    <item>60 days</item>
    <item>90 days</item>
</string-array>
<string-array name="recycle_bin_auto_clear_days_values">
    <item>7</item>
    <item>14</item>
    <item>30</item>
    <item>60</item>
    <item>90</item>
</string-array>
```

- [ ] **Step 4: Wire preferences in `PreferencesFragment.initPreferences()`**:

```kotlin
with(requirePreference(R.string.pk_recycle_bin_auto_clear)) {
    this as SwitchPreferenceCompat
    bindToSubject(recycleBinPreferences.autoClearEnabled, viewLifecycleOwner)
}
with(requirePreference(R.string.pk_recycle_bin_auto_clear_days)) {
    this as ListPreference
    value = (recycleBinPreferences.autoClearDays.value ?: 30).toString()
    setOnPreferenceChangeListener { _, newValue ->
        recycleBinPreferences.autoClearDays.onNext((newValue as String).toInt())
        true
    }
}
```

- [ ] **Step 5: Build and commit**

---
### Task 11: i18n Strings — English and Chinese

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-b+zh+Hans/strings.xml`
- (Optional) Modify: `app/src/main/res/values/arrays.xml`

- [ ] **Step 1: Add English strings** in `res/values/strings.xml`:

```xml
<!-- Recycle Bin -->
<string name="recycle_bin_title">Recycle Bin</string>
<string name="recycle_bin_empty">Recycle Bin is empty</string>
<string name="move_to_recycle_bin">Move to Recycle Bin</string>
<string name="gallery_delete_to_recycle_bin_single">Move this item to the Recycle Bin?</string>
<string name="gallery_delete_to_recycle_bin_plural">Move %1$d items to the Recycle Bin?</string>
<string name="media_viewer_delete_to_recycle_bin">Move this item to the Recycle Bin? It will be automatically cleared after %1$d days.</string>
<string name="recycle_bin_auto_clear_hint">They will be automatically cleared after %1$d days.</string>
<string name="restore">Restore</string>
<string name="permanent_delete">Delete permanently</string>
<string name="permanent_delete_confirmation">Delete these items forever? This cannot be undone.</string>
<string name="empty_recycle_bin">Empty Recycle Bin</string>
<string name="empty_recycle_bin_confirmation">Permanently delete all items in the Recycle Bin?</string>
<string name="recycle_bin_auto_clear_title">Auto-clear Recycle Bin</string>
<string name="recycle_bin_auto_clear_summary">Automatically permanently delete items after the set period</string>
<string name="recycle_bin_auto_clear_days_title">Auto-clear period</string>
<!-- Remove or replace old archive string -->
<string name="archive_action">Archive</string>  <!-- KEEP for backward compat, but no longer shown in UI -->
```

- [ ] **Step 2: Add Chinese strings** in `res/values-b+zh+Hans/strings.xml`:

```xml
<string name="recycle_bin_title">回收站</string>
<string name="recycle_bin_empty">回收站是空的</string>
<string name="move_to_recycle_bin">移入回收站</string>
<string name="gallery_delete_to_recycle_bin_single">将这张图片移入回收站？</string>
<string name="gallery_delete_to_recycle_bin_plural">将 %1$d 张图片移入回收站？</string>
<string name="media_viewer_delete_to_recycle_bin">将这张图片移入回收站？%1$d 天后将自动清空。</string>
<string name="recycle_bin_auto_clear_hint">%1$d 天后将自动清空。</string>
<string name="restore">还原</string>
<string name="permanent_delete">彻底删除</string>
<string name="permanent_delete_confirmation">彻底删除这些图片？此操作不可撤销。</string>
<string name="empty_recycle_bin">清空回收站</string>
<string name="empty_recycle_bin_confirmation">确定要永久删除回收站中的所有图片？</string>
<string name="recycle_bin_auto_clear_title">自动清空回收站</string>
<string name="recycle_bin_auto_clear_summary">超过设定天数后自动永久删除回收站中的图片</string>
<string name="recycle_bin_auto_clear_days_title">自动清空天数</string>
```

- [ ] **Step 3: Build and commit**

---
### Task 12: Feature Module — Wire All Dependencies

**Files:**
- Create: `app/src/main/java/ua/com/radiokot/photoprism/features/recyclebin/di/RecycleBinFeatureModule.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/GalleryFeatureModule.kt`
- Modify: `app/src/main/java/ua/com/radiokot/photoprism/PhotoPrismGallery.kt` (app-level Koin init)

- [ ] **Step 1: Create DI module** for recycle bin:

```kotlin
val recycleBinFeatureModule = module {
    single {
        RecycleBinPreferencesOnPrefs(
            preferences = get(named(APP_NO_BACKUP_PREFERENCES)),
        )
    } bind RecycleBinPreferences::class

    scopedOf(::RestoreGalleryMediaUseCase)
    scopedOf(::AutoClearRecycleBinUseCase)

    viewModelOf(::RecycleBinViewModel)
}
```

- [ ] **Step 2: Include module** in app-level Koin init

- [ ] **Step 3: Build and commit**

---
### Task 13: Final Integration — Handle Album "Remove from Album" Correctness

**Files:** No changes needed (verification step)

**Context check:** After Task 5, `DeleteGalleryMediaUseCase` now archives instead of deleting. The `GalleryMediaRemoteActionsViewModelDelegateImpl` has separate `archiveGalleryMedia()` and `deleteGalleryMedia()` methods. After our changes:
- `deleteGalleryMedia()` → uses new `DeleteGalleryMediaUseCase` (archives)
- `archiveGalleryMedia()` → uses `ArchiveGalleryMediaUseCase` (also archives)

Both do the same thing now. We should remove `archiveGalleryMedia()` from the UI (Task 9 removes the menu item) but keep the use case for backward compat.

- [ ] **Step 1: Verify** that removing "Archive" from menus doesn't break compilation
- [ ] **Step 2: Verify** that the `ArchiveGalleryMediaUseCase` is still referenced (GalleryMediaRemoteActionsViewModelDelegateImpl has it) — it's fine, the code path still exists but no UI triggers it
- [ ] **Step 3: Full build and run**

```
./gradlew :app:assembleDebug
```

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat: integrate recycle bin feature - full implementation"
```