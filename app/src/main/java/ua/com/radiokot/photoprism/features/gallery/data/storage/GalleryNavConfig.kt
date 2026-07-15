package ua.com.radiokot.photoprism.features.gallery.data.storage

/**
 * 底部导航栏可配置的功能项。
 * PHOTOS 不可隐藏，始终显示在底部栏首位。
 */
enum class BottomNavItemId(
    val menuResId: Int,
    val iconRes: Int,
    val labelRes: Int,
) {
    PHOTOS(
        menuResId = ua.com.radiokot.photoprism.R.id.bottom_photos,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_film,
        labelRes = ua.com.radiokot.photoprism.R.string.library,
    ),
    SEARCH(
        menuResId = ua.com.radiokot.photoprism.R.id.bottom_search,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_search,
        labelRes = ua.com.radiokot.photoprism.R.string.search_the_library,
    ),
    ALBUMS(
        menuResId = ua.com.radiokot.photoprism.R.id.bottom_albums,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_album,
        labelRes = ua.com.radiokot.photoprism.R.string.albums,
    ),
    FAVORITES(
        menuResId = ua.com.radiokot.photoprism.R.id.favorites,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_favorite,
        labelRes = ua.com.radiokot.photoprism.R.string.favorites,
    ),
    PLACES(
        menuResId = ua.com.radiokot.photoprism.R.id.places,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_map,
        labelRes = ua.com.radiokot.photoprism.R.string.map,
    ),
    CALENDAR(
        menuResId = ua.com.radiokot.photoprism.R.id.calendar,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_calendar,
        labelRes = ua.com.radiokot.photoprism.R.string.calendar,
    ),
    LABELS(
        menuResId = ua.com.radiokot.photoprism.R.id.labels,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_label,
        labelRes = ua.com.radiokot.photoprism.R.string.labels,
    ),
    PEOPLE(
        menuResId = ua.com.radiokot.photoprism.R.id.people,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_people,
        labelRes = ua.com.radiokot.photoprism.R.string.people,
    ),
    FOLDERS(
        menuResId = ua.com.radiokot.photoprism.R.id.folders,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_folder,
        labelRes = ua.com.radiokot.photoprism.R.string.folders,
    ),
    UPLOAD(
        menuResId = ua.com.radiokot.photoprism.R.id.upload,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_upload,
        labelRes = ua.com.radiokot.photoprism.R.string.upload,
    ),
    PREFERENCES(
        menuResId = ua.com.radiokot.photoprism.R.id.preferences,
        iconRes = ua.com.radiokot.photoprism.R.drawable.ic_gear,
        labelRes = ua.com.radiokot.photoprism.R.string.preferences,
    ),
    ;

    companion object {
        /** 默认底部栏配置：图库 | 相册 | 收藏 */
        val DEFAULT = listOf(PHOTOS, ALBUMS, FAVORITES)

        /** 底部栏最多显示项数（不含"更多"） */
        const val MAX_VISIBLE_ITEMS = 4

        /** 不可从底部栏隐藏的项 */
        val LOCKED_ITEMS = setOf(PHOTOS)

        /** 所有可选入底部栏的项 */
        val ALL_ITEMS = values().toList()
    }
}
