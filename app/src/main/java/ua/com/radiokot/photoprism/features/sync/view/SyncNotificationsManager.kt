package ua.com.radiokot.photoprism.features.sync.view

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.features.gallery.view.GalleryActivity
import ua.com.radiokot.photoprism.features.sync.logic.SyncWorker

class SyncNotificationsManager(
    private val context: Context,
) {
    private val notificationsManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(context.getString(R.string.sync_notification_channel))
            .setShowBadge(false)
            .build()
        notificationsManager.createNotificationChannel(channel)
    }

    fun getSyncProgressNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.sync_notification_progress_title))
            .setContentText(context.getString(R.string.sync_notification_preparing))
            .setProgress(0, 0, true)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            .setSmallIcon(R.drawable.ic_upload_white)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(
                0,
                context.getString(R.string.sync_notification_pause),
                createPausePendingIntent(),
            )
            .build()
    }

    fun notifyPreparing() {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.sync_notification_progress_title))
            .setContentText(context.getString(R.string.sync_notification_preparing))
            .setProgress(0, 0, true)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            .setSmallIcon(R.drawable.ic_upload_white)
            .setOngoing(true)
            .addAction(
                0,
                context.getString(R.string.sync_notification_pause),
                createPausePendingIntent(),
            )
            .build()
        notificationsManager.notify(SYNC_PROGRESS_NOTIFICATION_ID, notification)
    }

    /**
     * Updates the sync progress notification to show the processing (server-side indexing) phase.
     * Uses an indeterminate progress bar and a "Processing on server…" message.
     *
     * @param syncedCount total files synced across all folders so far
     * @param totalFolders total number of enabled folders
     * @param folderIndex which folder is being processed (0-based)
     */
    fun notifySyncProcessing(
        uploadToken: String,
        folderName: String?,
        syncedCount: Int,
        totalFolders: Int,
        folderIndex: Int,
    ) {
        ensureChannel()
        val title = if (folderName != null) {
            context.getString(R.string.sync_notification_processing_folder, folderName)
        } else {
            context.getString(R.string.sync_notification_processing)
        }

        val content = if (totalFolders <= 1) {
            context.resources.getQuantityString(
                R.plurals.sync_notification_progress_count,
                syncedCount,
                syncedCount,
            )
        } else {
            context.getString(
                R.string.sync_notification_progress_folder_index,
                folderIndex + 1,
                totalFolders,
                syncedCount,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(0, 0, true) // indeterminate
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            .setSmallIcon(R.drawable.ic_upload_white)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(
                0,
                context.getString(R.string.sync_notification_pause),
                createPausePendingIntent(),
            )
            .build()

        notificationsManager.notify(SYNC_PROGRESS_NOTIFICATION_ID, notification)
    }

    fun notifySyncProgress(
        uploadToken: String,
        folderName: String?,
        percent: Double,
        syncedCount: Int,
        totalFolders: Int,
        folderIndex: Int,
    ) {
        ensureChannel()
        val title = if (folderName != null) {
            context.getString(R.string.sync_notification_progress_folder, folderName)
        } else {
            context.getString(R.string.sync_notification_progress_title)
        }

        val isIndeterminate = percent < 0.0
        val content = if (isIndeterminate || totalFolders <= 1) {
            context.resources.getQuantityString(
                R.plurals.sync_notification_progress_count,
                syncedCount,
                syncedCount,
            )
        } else {
            context.getString(
                R.string.sync_notification_progress_folder_index,
                folderIndex + 1,
                totalFolders,
                syncedCount,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(
                if (isIndeterminate) 0 else 100,
                if (isIndeterminate) 0 else percent.toInt().coerceIn(0, 100),
                isIndeterminate,
            )
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            .setSmallIcon(R.drawable.ic_upload_white)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(
                0,
                context.getString(R.string.sync_notification_pause),
                createPausePendingIntent(),
            )
            .build()

        notificationsManager.notify(SYNC_PROGRESS_NOTIFICATION_ID, notification)
    }

    fun notifySyncPaused(uploadToken: String, syncedCount: Int) {
        ensureChannel()
        notificationsManager.cancel(SYNC_PROGRESS_NOTIFICATION_ID)

        val contentTitle = context.getString(R.string.sync_paused_title)
        val contentText = context.resources.getQuantityString(
            R.plurals.sync_notification_progress_count,
            syncedCount,
            syncedCount,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            .setSmallIcon(R.drawable.ic_upload_white)
            .setAutoCancel(true)
            .addAction(
                0,
                context.getString(R.string.sync_notification_resume),
                createResumePendingIntent(),
            )
            .build()

        notificationsManager.notify(SYNC_PAUSED_NOTIFICATION_ID, notification)
    }

    fun notifySyncComplete(
        uploadToken: String,
        fileCount: Int,
    ) {
        ensureChannel()
        notificationsManager.cancel(SYNC_PROGRESS_NOTIFICATION_ID)

        val contentTitle = context.getString(R.string.sync_complete_title)
        val contentText = context.getString(R.string.sync_complete_text, fileCount)

        val openGalleryIntent = Intent(context, GalleryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            uploadToken.hashCode(),
            openGalleryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            .setSmallIcon(R.drawable.ic_upload_white)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationsManager.notify(toPositiveId(uploadToken), notification)
    }

    fun notifySyncFailed(
        uploadToken: String,
        syncedCount: Int,
        failedCount: Int,
    ) {
        ensureChannel()
        notificationsManager.cancel(SYNC_PROGRESS_NOTIFICATION_ID)

        val contentTitle = context.getString(R.string.sync_failed_title)
        val contentText = if (syncedCount > 0) {
            context.getString(R.string.sync_failed_partial_text, syncedCount, failedCount)
        } else {
            context.getString(R.string.sync_failed_text, failedCount)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            .setSmallIcon(R.drawable.ic_upload_white)
            .setAutoCancel(true)
            .build()

        notificationsManager.notify(toPositiveId(uploadToken), notification)
    }

    private fun toPositiveId(token: String): Int =
        token.hashCode() and Int.MAX_VALUE

    fun cancelSyncNotification() {
        notificationsManager.cancel(SYNC_PROGRESS_NOTIFICATION_ID)
    }

    private fun createPausePendingIntent(): PendingIntent {
        val intent = Intent(context, SyncPauseReceiver::class.java).apply {
            action = ACTION_PAUSE_SYNC
        }
        return PendingIntent.getBroadcast(
            context,
            PAUSE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createResumePendingIntent(): PendingIntent {
        val intent = Intent(context, SyncPauseReceiver::class.java).apply {
            action = ACTION_RESUME_SYNC
        }
        return PendingIntent.getBroadcast(
            context,
            RESUME_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val SYNC_PROGRESS_NOTIFICATION_ID = 2001
        const val SYNC_PAUSED_NOTIFICATION_ID = 2002
        const val ACTION_PAUSE_SYNC = "ua.com.radiokot.photoprism.action.PAUSE_SYNC"
        const val ACTION_RESUME_SYNC = "ua.com.radiokot.photoprism.action.RESUME_SYNC"
        private const val PAUSE_REQUEST_CODE = 9002
        private const val RESUME_REQUEST_CODE = 9003
        private const val CHANNEL_ID = "sync"
    }
}

/** BroadcastReceiver that handles pause and resume actions from sync notifications. */
class SyncPauseReceiver : BroadcastReceiver(), org.koin.core.component.KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SyncNotificationsManager.ACTION_PAUSE_SYNC -> {
                WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.TAG)
                // Cancel the progress notification; the Worker's .doOnTerminate
                // will update the UI state when the cancellation propagates.
                val manager: SyncNotificationsManager = getKoin().get()
                manager.notifySyncPaused(
                    uploadToken = "paused_${System.currentTimeMillis()}",
                    syncedCount = 0,
                )
            }
            SyncNotificationsManager.ACTION_RESUME_SYNC -> {
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
                val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .addTag(SyncWorker.TAG)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    SyncWorker.TAG,
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }
}
