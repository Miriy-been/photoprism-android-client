package ua.com.radiokot.photoprism.features.gallery.data.storage

import android.content.SharedPreferences
import io.reactivex.rxjava3.subjects.BehaviorSubject
import ua.com.radiokot.photoprism.util.stringifyPreferenceSubject

class GalleryNavPreferencesOnPrefs(
    private val preferences: SharedPreferences,
) : GalleryNavPreferences {

    override val bottomNavItems: BehaviorSubject<List<BottomNavItemId>> =
        stringifyPreferenceSubject(
            preferences = preferences,
            key = "gallery_bottom_nav_items",
            defaultValue = BottomNavItemId.DEFAULT,
            stringSerializer = { items ->
                items.joinToString(",") { it.name }
            },
            stringDeserializer = { str ->
                if (str.isBlank()) {
                    BottomNavItemId.DEFAULT
                } else {
                    str.split(",").mapNotNull { name ->
                        try {
                            BottomNavItemId.valueOf(name.trim())
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }.ifEmpty {
                        BottomNavItemId.DEFAULT
                    }
                }
            },
        )
}
