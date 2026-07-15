package ua.com.radiokot.photoprism.features.gallery.search.data.storage

import android.content.SharedPreferences
import androidx.core.content.edit
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import ua.com.radiokot.photoprism.di.JsonObjectMapper
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig

/**
 * Repository for lightweight search history.
 * Stores recent search configurations in SharedPreferences as JSON.
 */
class SearchHistoryRepository(
    private val preferences: SharedPreferences,
    private val jsonObjectMapper: JsonObjectMapper,
) {
    private val key = "search_history_v1"
    private val maxEntries = 10

    /**
     * Add a search config to history.
     * Deduplicates by config equality and moves to top.
     */
    fun add(config: SearchConfig) {
        val entries = getAllInternal().toMutableList()

        // Remove existing entry with the same config
        entries.removeAll { it.searchConfig == config }

        // Add to front
        entries.add(
            0,
            StoredHistoryEntry(
                searchConfig = config,
                timestamp = System.currentTimeMillis(),
                label = generateLabel(config),
            )
        )

        // Trim to max entries
        val trimmed = entries.take(maxEntries)

        saveAll(trimmed)
    }

    /**
     * Get all history entries, newest first.
     */
    fun getAll(): List<SearchHistoryEntry> {
        return getAllInternal().map { stored ->
            SearchHistoryEntry(
                searchConfig = stored.searchConfig,
                timestamp = stored.timestamp,
                label = stored.label,
            )
        }
    }

    /**
     * Clear all search history.
     */
    fun clear() {
        preferences.edit {
            remove(key)
        }
    }

    private fun getAllInternal(): List<StoredHistoryEntry> {
        val json = preferences.getString(key, null) ?: return emptyList()
        return try {
            jsonObjectMapper.readValue(json, object : TypeReference<List<StoredHistoryEntry>>() {})
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveAll(entries: List<StoredHistoryEntry>) {
        val json = jsonObjectMapper.writeValueAsString(entries)
        preferences.edit {
            putString(key, json)
        }
    }

    private fun generateLabel(config: SearchConfig): String {
        val parts = mutableListOf<String>()

        if (config.onlyFavorite) {
            parts.add("⭐")
        }
        if (config.includePrivate) {
            parts.add("🔒")
        }
        config.mediaTypes?.forEach { type ->
            when (type) {
                GalleryMedia.TypeName.IMAGE -> parts.add("\uD83D\uDDBC")
                GalleryMedia.TypeName.VIDEO -> parts.add("\uD83C\uDFA5")
                GalleryMedia.TypeName.ANIMATED -> parts.add("\uD83C\uDFAC")
                GalleryMedia.TypeName.LIVE -> parts.add("\uD83D\uDCF7")
                else -> {}
            }
        }
        if (config.albumUid != null) {
            parts.add("\uD83D\uDCC1")
        }
        if (config.personIds.isNotEmpty()) {
            parts.add("\uD83D\uDC64")
        }
        if (config.userQuery.isNotBlank()) {
            parts.add("\uD83D\uDD0D")
        }

        val label = parts.joinToString(" ")
        val queryPart = if (config.userQuery.isNotBlank()) {
            if (label.isNotEmpty()) "$label ${config.userQuery}" else config.userQuery
        } else {
            label
        }

        return queryPart.ifEmpty { "所有内容" }.take(40)
    }

    data class SearchHistoryEntry(
        val searchConfig: SearchConfig,
        val timestamp: Long,
        val label: String?,
    )

    private data class StoredHistoryEntry
    @JsonCreator
    constructor(
        @JsonProperty("sc")
        val searchConfig: SearchConfig,
        @JsonProperty("ts")
        val timestamp: Long,
        @JsonProperty("lb")
        val label: String?,
    )
}
