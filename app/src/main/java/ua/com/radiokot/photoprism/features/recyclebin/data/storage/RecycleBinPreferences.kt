package ua.com.radiokot.photoprism.features.recyclebin.data.storage

import android.content.SharedPreferences
import io.reactivex.rxjava3.subjects.BehaviorSubject
import ua.com.radiokot.photoprism.util.booleanPreferenceSubject
import ua.com.radiokot.photoprism.util.stringifyPreferenceSubject

/**
 * 回收站自动清理偏好设置。
 * 控制是否启用自动清理以及保留天数。
 */
interface RecycleBinPreferences {
    /**
     * 是否启用自动清理。
     * 启用后，超出保留天数的项目将被永久删除。
     */
    val autoClearEnabled: BehaviorSubject<Boolean>

    /**
     * 自动清理的保留天数。
     * 项目在回收站中保留超过此天数后将被自动清除。
     * 仅在 [autoClearEnabled] 为 true 时生效。
     */
    val autoClearDays: BehaviorSubject<Int>
}

class RecycleBinPreferencesOnPrefs(
    preferences: SharedPreferences,
) : RecycleBinPreferences {
    override val autoClearEnabled: BehaviorSubject<Boolean> =
        booleanPreferenceSubject(
            preferences = preferences,
            key = "recycle_bin_auto_clear",
            defaultValue = true,
        )

    override val autoClearDays: BehaviorSubject<Int> =
        stringifyPreferenceSubject(
            preferences = preferences,
            key = "recycle_bin_auto_clear_days",
            defaultValue = 30,
            stringSerializer = Int::toString,
            stringDeserializer = { str ->
                str.toIntOrNull()
            },
        )
}