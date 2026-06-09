package ua.com.radiokot.photoprism.features.upload.view.model

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.di.JsonObjectMapper
import ua.com.radiokot.photoprism.env.data.model.EnvSession
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.isSelfPermissionGranted
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.features.importt.logic.ImportFilesWorker
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.importt.model.sizeMb
import ua.com.radiokot.photoprism.features.albums.data.storage.AlbumsRepository
import java.io.File

class UploadFilesViewModel(
    private val albumsRepository: AlbumsRepository,
    private val session: EnvSession,
    private val jsonObjectMapper: JsonObjectMapper,
    application: Application,
) : AndroidViewModel(application) {
    private val log = kLogger("UploadFilesVM")
    private val context = getApplication<Application>()
    private val permissionsToCheckBeforeStart = mutableListOf<String>()
    private var albums: Set<DestinationAlbum> = emptySet()
    private val uploadUniqueName = "${ImportFilesWorker.TAG}:${System.currentTimeMillis()}"

    val selectedImages = MutableLiveData<List<ImportableFile>>(emptyList())
    val summary: MutableLiveData<Summary> = MutableLiveData()
    val isNotificationPermissionRationaleVisible: MutableLiveData<Boolean> = MutableLiveData(false)
    private val eventsSubject = PublishSubject.create<Event>()
    val events = eventsSubject.observeOnMain()
    val isUploadButtonEnabled: MutableLiveData<Boolean> = MutableLiveData(true)

    fun initWithImages(contentUris: List<Uri>, contentResolver: ContentResolver) {
        val files = contentUris.mapNotNull { uri ->
            try {
                val displayName = getFileName(contentResolver, uri) ?: uri.lastPathSegment ?: "unknown"
                val size = getFileSize(contentResolver, uri)
                val mimeType = contentResolver.getType(uri)

                ImportableFile(
                    contentUri = uri.toString(),
                    displayName = displayName,
                    mimeType = mimeType,
                    size = size,
                )
            } catch (e: Exception) {
                log.warn(e) { "initWithImages(): failed_to_read_file:$uri" }
                null
            }
        }

        selectedImages.value = files

        summary.value = Summary(
            libraryRootUrl = session.envConnectionParams.rootUrl.toString(),
            fileCount = files.size,
            sizeMb = files.sizeMb,
        )

        checkPermissions()

        albumsRepository.updateIfNotFresh()

        log.debug {
            "initWithImages(): initialized:" +
                    "\nfileCount=${files.size}," +
                    "\nsizeMb=${files.sizeMb}," +
                    "\npermissionsToCheckBeforeStart=$permissionsToCheckBeforeStart"
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex("_display_name")
            if (nameIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    }

    private fun getFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex("_size")
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                cursor.getLong(sizeIndex)
            } else {
                0L
            }
        } ?: 0L
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && !context.isSelfPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            isNotificationPermissionRationaleVisible.value = true
            permissionsToCheckBeforeStart += Manifest.permission.POST_NOTIFICATIONS
        }
    }

    fun onUploadClicked() {
        if (permissionsToCheckBeforeStart.isNotEmpty()) {
            log.debug { "onUploadClicked(): requesting_permissions_first" }
            eventsSubject.onNext(Event.RequestPermissions(permissionsToCheckBeforeStart.toTypedArray()))
        } else {
            log.debug { "onUploadClicked(): starting_upload_in_background" }
            startUploadInBackgroundAndFinishAsync()
                .subscribe()
                .autoDispose(this)
        }
    }

    fun onCancelClicked() {
        log.debug { "onCancelClicked(): finishing" }
        eventsSubject.onNext(Event.Finish)
    }

    fun onAlbumsClicked() {
        log.debug { "onAlbumsClicked(): opening_selection" }
        eventsSubject.onNext(
            Event.OpenAlbumSelectionForResult(
                currentlySelectedAlbums = albums,
            )
        )
    }

    fun removeImage(index: Int) {
        val current = selectedImages.value?.toMutableList() ?: return
        if (index in current.indices) {
            current.removeAt(index)
            selectedImages.value = current
            updateSummary()
        }
    }

    fun onAlbumSelectionResult(selectedAlbums: Set<DestinationAlbum>) {
        log.debug { "onAlbumSelectionResult(): updating_selection:${selectedAlbums.size}" }
        this.albums = selectedAlbums
        updateSummary()
    }

    fun onPermissionsResult(results: Map<String, Boolean>) {
        log.debug { "onPermissionsResult(): result_received:${results.entries}" }
        startUploadInBackgroundAndFinishAsync()
            .subscribe()
            .autoDispose(this)
    }

    private fun updateSummary() {
        val files = selectedImages.value ?: emptyList()
        summary.value = Summary(
            libraryRootUrl = session.envConnectionParams.rootUrl.toString(),
            fileCount = files.size,
            sizeMb = files.sizeMb,
            albums = albums.map(DestinationAlbum::title),
        )
    }

    private fun startUploadInBackgroundAndFinishAsync(): Completable {
        return Completable.fromAction {
            eventsSubject.onNext(Event.ShowStartedInBackgroundMessage)
            isUploadButtonEnabled.postValue(false)

            val files = selectedImages.value ?: emptyList()
            if (files.isEmpty()) {
                log.debug { "startUploadInBackgroundAndFinishAsync(): no_files_to_upload" }
                eventsSubject.onNext(Event.Finish)
                return@fromAction
            }

            files.forEach { file ->
                context.grantUriPermission(
                    context.packageName,
                    file.contentUri.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }

            val fileListJsonFile = File(
                getApplication<Application>().noBackupFilesDir,
                "${uploadUniqueName}.json"
            )
            jsonObjectMapper.writeValue(fileListJsonFile, files)

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    uploadUniqueName,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ImportFilesWorker>()
                        .setInputData(
                            ImportFilesWorker.getInputData(
                                fileListJsonFile = fileListJsonFile,
                                albums = albums,
                                jsonObjectMapper = jsonObjectMapper,
                            )
                        )
                        .addTag(ImportFilesWorker.TAG)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                )

            log.debug { "startUploadInBackgroundAndFinishAsync(): finishing_after_start" }
            eventsSubject.onNext(Event.Finish)
        }.subscribeOn(Schedulers.io())
    }

    data class Summary(
        val libraryRootUrl: String,
        val fileCount: Int,
        val sizeMb: Double,
        val albums: Collection<String> = emptySet(),
    )

    sealed interface Event {
        object Finish : Event
        object ShowStartedInBackgroundMessage : Event

        class RequestPermissions(
            val permissions: Array<String>,
        ) : Event

        class OpenAlbumSelectionForResult(
            val currentlySelectedAlbums: Set<DestinationAlbum>,
        ) : Event
    }
}