package ua.com.radiokot.photoprism.features.sync.view

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves the current sync progress so that [SyncPauseReceiver]
     * can display the real synced count in the paused notification.
     */
    fun saveSyncProgress(synced: Int) {
        prefs.edit().putInt(KEY_SYNCED_COUNT, synced).apply()
    }

    /**
     * Retrieves the last saved sync progress.
     */
    fun getSyncProgress(): Int {
        return prefs.getInt(KEY_SYNCED_COUNT, 0)
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
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.sync_notification_progress_count,
                    0,
                    0,
                )
            )
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

    fun notifySyncProgress(
        uploadToken: String,
        folderName: String?,
        syncedCount: Int,
        totalFiles: Int = 0,
    ) {
        ensureChannel()
        val title = if (folderName != null) {
            context.getString(R.string.sync_notification_progress_folder, folderName)
        } else {
            context.getString(R.string.sync_notification_progress_title)
        }
        val content = context.resources.getQuantityString(
            R.plurals.sync_notification_progress_count,
            syncedCount,
            syncedCount,
        )

        // Feature 1: Use determinate progress bar when we know the total
        val hasTotal = totalFiles > 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(
                if (hasTotal) totalFiles else 0,
                if (hasTotal) syncedCount else 0,
                !hasTotal,  // indeterminate only when total is unknown
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

        notificationsManager.notify(uploadToken.hashCode(), notification)
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

        notificationsManager.notify(uploadToken.hashCode(), notification)
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

        notificationsManager.notify(uploadToken.hashCode(), notification)
    }

    fun cancelSyncNotification() {
        notificationsManager.cancel(SYNC_PROGRESS_NOTIFICATION_ID)
    }

    companion object {
        const val SYNC_PROGRESS_NOTIFICATION_ID = 2001
        const val ACTION_PAUSE_SYNC = "ua.com.radiokot.photoprism.action.PAUSE_SYNC"
        const val ACTION_RESUME_SYNC = "ua.com.radiokot.photoprism.action.RESUME_SYNC"
        private const val PAUSE_REQUEST_CODE = 9002
        private const val RESUME_REQUEST_CODE = 9003
        private const val CHANNEL_ID = "sync"
        private const val PREFS_NAME = "sync_notifications"
        private const val KEY_SYNCED_COUNT = "synced_count"
    }
}

/** BroadcastReceiver that handles pause and resume actions from sync notifications. */
class SyncPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SyncNotificationsManager.ACTION_PAUSE_SYNC -> {
                WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.TAG)
                // Show "paused" notification with the real synced count.
                val manager = SyncNotificationsManager(context)
                manager.notifySyncPaused(
                    uploadToken = "paused_${System.currentTimeMillis()}",
                    syncedCount = manager.getSyncProgress(),
                )
            }
            SyncNotificationsManager.ACTION_RESUME_SYNC -> {
                // Re-enqueue the sync worker (same pattern as SyncSettingsViewModel).
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
