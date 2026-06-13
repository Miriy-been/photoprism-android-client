package ua.com.radiokot.photoprism.features.sync.view

import android.os.Bundle
import android.widget.TextView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivitySyncHistoryBinding
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.sync.data.model.SyncHistoryItem
import ua.com.radiokot.photoprism.features.sync.data.storage.SyncHistoryItemDao
import ua.com.radiokot.photoprism.features.sync.view.model.SyncHistoryDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncHistoryActivity : BaseActivity() {
    private lateinit var binding: ActivitySyncHistoryBinding
    private val syncHistoryDao: SyncHistoryItemDao by inject()
    private val disposables = CompositeDisposable()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val log = kLogger("SyncHistoryActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Force-inject Koin scoped dependencies on the main thread.
        // Lazy injection from a background thread would crash with:
        // "Method addObserver must be called on the main thread"
        syncHistoryDao

        loadHistory()
    }

    private fun loadHistory() {
        Single.fromCallable {
            val items = runBlocking { syncHistoryDao.getRecent(MAX_HISTORY) }
            items.map { item ->
                SyncHistoryDisplay(
                    startedAt = dateFormat.format(Date(item.startedAt)),
                    syncedCount = item.syncedCount,
                    failedCount = item.failedCount,
                    totalFiles = item.totalFiles,
                    status = when (item.status) {
                        SyncHistoryItem.STATUS_COMPLETED -> "✓"
                        SyncHistoryItem.STATUS_PARTIAL -> "!"
                        else -> "✗"
                    }
                )
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ history ->
                renderHistory(history)
            }, { error ->
                log.error(error) { "loadHistory(): failed" }
                binding.tvHistoryEmpty.text = getString(ua.com.radiokot.photoprism.R.string.sync_history_load_failed)
                binding.tvHistoryEmpty.visibility = android.view.View.VISIBLE
            })
            .addTo(disposables)
    }

    private fun renderHistory(history: List<SyncHistoryDisplay>) {
        val container = binding.layoutHistoryContent
        val emptyView = binding.tvHistoryEmpty

        if (history.isEmpty()) {
            emptyView.visibility = android.view.View.VISIBLE
            for (i in container.childCount - 1 downTo 1) {
                container.removeViewAt(i)
            }
            return
        }

        emptyView.visibility = android.view.View.GONE

        // Remove all existing rows
        for (i in container.childCount - 1 downTo 1) {
            container.removeViewAt(i)
        }

        for (item in history) {
            val row = layoutInflater.inflate(
                ua.com.radiokot.photoprism.R.layout.list_item_sync_folder,
                container,
                false
            ) as android.widget.LinearLayout

            val nameTv = row.findViewById<TextView>(
                ua.com.radiokot.photoprism.R.id.tv_folder_name
            )
            val pathTv = row.findViewById<TextView>(
                ua.com.radiokot.photoprism.R.id.tv_folder_path
            )
            val badgeTv = row.findViewById<TextView>(
                ua.com.radiokot.photoprism.R.id.tv_pending_badge
            )
            val checkBox = row.findViewById<android.widget.CheckBox>(
                ua.com.radiokot.photoprism.R.id.cb_enabled
            )

            checkBox.visibility = android.view.View.GONE

            nameTv.text = item.folderName ?: item.startedAt
            val syncText = if (item.totalFiles == 0) {
                getString(ua.com.radiokot.photoprism.R.string.sync_history_no_new_files)
            } else {
                getString(
                    ua.com.radiokot.photoprism.R.string.sync_history_item_format,
                    item.syncedCount,
                    item.totalFiles,
                )
            }
            pathTv.text = syncText + if (item.failedCount > 0) {
                " " + getString(ua.com.radiokot.photoprism.R.string.sync_history_item_failed, item.failedCount)
            } else {
                ""
            }
            badgeTv.text = item.status
            badgeTv.visibility = android.view.View.VISIBLE
            badgeTv.setBackgroundResource(
                if (item.failedCount > 0)
                    android.R.color.transparent
                else
                    android.R.color.holo_green_light
            )

            // Allow clearing individual history items on long click
            row.setOnClickListener {
                // No-op for now
            }

            container.addView(row)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }

    companion object {
        private const val MAX_HISTORY = 200
    }
}
