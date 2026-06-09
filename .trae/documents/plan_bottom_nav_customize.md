# 实施计划：底部菜单栏自定义 + 上传视频修复

## 当前状态

Steps 1-4 已完成：

| Step | 内容 | 状态 |
|------|------|------|
| 1 | 上传视频 MIME type 修复 | completed |
| 2 | 数据层（GalleryNavConfig + GalleryNavPreferences + OnPrefs） | completed |
| 3 | 导航视图层（GalleryNavigationView + GalleryActivity） | completed |
| 4 | 自定义界面（CustomizeNavActivity + 布局） | completed |
| 5 | Koin DI 注册 | **pending** |
| 6 | 偏好设置入口 + Activity 注册 | **pending** |

---

## 剩余步骤

### Step 5：Koin DI 注册

**文件**: `app/src/main/java/ua/com/radiokot/photoprism/features/gallery/GalleryFeatureModule.kt`

`GalleryActivity`（第 91 行）和 `CustomizeNavActivity`（第 22 行）都通过 `by inject()` 使用 `GalleryNavPreferences`，但 DI 模块尚未注册绑定。

在 `GalleryPreferencesOnPrefs` 绑定（第 73-78 行）之后添加：

```kotlin
single {
    GalleryNavPreferencesOnPrefs(
        preferences = get(named(APP_NO_BACKUP_PREFERENCES)),
    )
} bind GalleryNavPreferences::class
```

需添加 import：
```kotlin
import ua.com.radiokot.photoprism.features.gallery.data.storage.GalleryNavPreferences
import ua.com.radiokot.photoprism.features.gallery.data.storage.GalleryNavPreferencesOnPrefs
```

---

### Step 6A：字符串资源

**文件 1**: `app/src/main/res/values/preference_keys.xml`

在 `pk_custom_map_style` 之后添加：
```xml
<string name="pk_customize_bottom_nav" translatable="false">customize_bottom_nav</string>
```

**文件 2**: `app/src/main/res/values/strings.xml`（default 英文）

添加可翻译标题字符串（放在合适的分类下）：
```xml
<string name="customize_bottom_nav_title">Customize Bottom Navigation</string>
<string name="customize_bottom_nav_summary">Drag to reorder, toggle to show or hide</string>
```

**文件 3**: `app/src/main/res/values-b+zh+Hans/strings.xml`（中文翻译）

```xml
<string name="customize_bottom_nav_title">自定义底部菜单栏</string>
<string name="customize_bottom_nav_summary">拖拽排序，勾选显示或隐藏</string>
```

> 注意：其他语言文件（zh-Hant 等）可暂用英文 fallback，后续再翻译。

---

### Step 6B：偏好设置入口

**文件**: `app/src/main/res/xml/preferences.xml`

在 `viewing_preferences` 分类下的第一个 ListPreference 之前（或 General 分类末尾）添加：

```xml
<Preference
    app:iconSpaceReserved="false"
    app:key="@string/pk_customize_bottom_nav"
    app:persistent="false"
    app:summary="@string/customize_bottom_nav_summary"
    app:title="@string/customize_bottom_nav_title" />
```

建议放在 `General` PreferenceCategory 末尾（第 31 行 `</PreferenceCategory>` 之前），在 extensions 之后。

---

### Step 6C：PreferencesFragment 点击绑定

**文件**: `app/src/main/java/ua/com/radiokot/photoprism/features/prefs/view/PreferencesFragment.kt`

在 `initPreferences()` 方法内（如 `pk_library` 绑定之后、`pk_gallery_item_scale` 之前）添加：

```kotlin
with(requirePreference(R.string.pk_customize_bottom_nav)) {
    setOnPreferenceClickListener {
        startActivity(Intent(requireContext(), CustomizeNavActivity::class.java))
        true
    }
}
```

需添加 import：
```kotlin
import ua.com.radiokot.photoprism.features.prefs.navcustomize.view.CustomizeNavActivity
```

---

### Step 6D：AndroidManifest.xml Activity 注册

**文件**: `app/src/main/AndroidManifest.xml`

在 `UploadFilesActivity` 声明（第 212-216 行）之后添加：

```xml
<activity
    android:name=".features.prefs.navcustomize.view.CustomizeNavActivity"
    android:exported="false"
    android:label="@string/customize_bottom_nav_title"
    android:resizeableActivity="true" />
```

---

### Step 7：编译验证

```bash
./gradlew assembleDebug
```

验证点：
- 编译无错误
- 偏好设置中出现"自定义底部菜单栏"入口，点击跳转自定义页面
- 底部栏根据自定义配置动态变化
- 上传功能可选择视频文件

---

## 涉及文件总览

| # | 文件 | 操作 | 步骤 |
|---|------|------|------|
| 1 | `features/gallery/GalleryFeatureModule.kt` | 添加 Koin 绑定 | 5 |
| 2 | `res/values/preference_keys.xml` | 添加 key 字符串 | 6A |
| 3 | `res/values/strings.xml` | 添加英文字符串 | 6A |
| 4 | `res/values-b+zh+Hans/strings.xml` | 添加中文字符串 | 6A |
| 5 | `res/xml/preferences.xml` | 添加 Preference 入口 | 6B |
| 6 | `features/prefs/view/PreferencesFragment.kt` | 添加点击绑定 | 6C |
| 7 | `AndroidManifest.xml` | 注册 CustomizeNavActivity | 6D |
| 8 | `features/gallery/data/storage/GalleryNavConfig.kt` | 已完成，无需修改 | — |
| 9 | `features/gallery/data/storage/GalleryNavPreferences.kt` | 已完成，无需修改 | — |
| 10 | `features/gallery/data/storage/GalleryNavPreferencesOnPrefs.kt` | 已完成，无需修改 | — |
| 11 | `features/prefs/navcustomize/view/CustomizeNavActivity.kt` | 已完成，无需修改 | — |
| 12 | `features/gallery/view/GalleryNavigationView.kt` | 已完成，无需修改 | — |
| 13 | `features/gallery/view/GalleryActivity.kt` | 已完成，无需修改 | — |
| 14 | `features/upload/view/UploadFilesActivity.kt` | 已完成（type=`*/*`） | — |
