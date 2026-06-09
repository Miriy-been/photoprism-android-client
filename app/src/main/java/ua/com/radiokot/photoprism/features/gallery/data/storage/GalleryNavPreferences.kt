package ua.com.radiokot.photoprism.features.gallery.data.storage

import io.reactivex.rxjava3.subjects.BehaviorSubject

/**
 * 底部导航栏自定义配置的持久化接口。
 * 存储用户在底部栏显示哪些功能项及其顺序。
 */
interface GalleryNavPreferences {
    /**
     * 底部栏显示的功能项列表（按顺序）。
     * BehaviorSubject 支持响应式订阅 — 配置变化自动重建导航。
     */
    val bottomNavItems: BehaviorSubject<List<BottomNavItemId>>
}
