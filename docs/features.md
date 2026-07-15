# Gallery for PhotoPrism — 功能介绍

> 基于 [photoprism-android-client](https://github.com/Radiokot/photoprism-android-client) v1.43.0

---

## 1. 环境连接 (envconnection)

连接 PhotoPrism 服务器的入口模块。

| 项目 | 说明 |
|------|------|
| Activity | `EnvConnectionActivity` |
| ViewModel | `EnvConnectionViewModel` |
| UseCase | `ConnectToEnvUseCase`, `DisconnectFromEnvUseCase` |
| DI | `EnvConnectionFeatureModule` |

**功能细节**：
- 支持多种认证方式：Session Cookie 登录、mTLS（双向 TLS）、HTTP Basic Auth、SSO（Authelia/Cloudflare Access 等）
- 两步验证（TFA）码输入对话框
- URL 格式校验、密码校验、连接状态管理
- 长按标题可连接演示服务器
- 断开连接时清理缓存目录、Cookie、Memories 数据
- 外部可通过 Intent Extra 传入 `rootUrl` 直接跳转到连接界面

---

## 2. 核心图库 (gallery)

主界面，提供照片/视频的时间线浏览。

| 项目 | 说明 |
|------|------|
| Activity | `GalleryActivity`, `GallerySingleRepositoryActivity` |
| ViewModel | `GalleryViewModel`, `GalleryListViewModelImpl`, `GalleryFastScrollViewModel`, `GalleryMediaDownloadActionsViewModelDelegateImpl`, `GalleryMediaRemoteActionsViewModelDelegateImpl`, `GallerySingleRepositoryViewModel`, `DownloadProgressViewModel` |
| UseCase | `AddGalleryMediaToAlbumUseCase`, `ArchiveGalleryMediaUseCase`, `DeleteGalleryMediaUseCase`, `DownloadFileUseCase`, `RemoveGalleryMediaFromAlbumUseCase`, `FileReturnIntentCreator`, `GalleryMonthsSequence`, `MediaPreviewUrlFactory`, `MediaFileDownloadUrlFactory`, `MediaWebUrlFactory`, `SearchBookmarkShortcutsManager`, `VideoFormatSupport` |
| DI | `GalleryFeatureModule` |

**功能细节**：
- 媒体时间线，5 种网格大小选项，按日/月分组
- 快速滚动条，可快速跳转到指定月份
- 拖拽选择（Drag Select）
- 抽屉导航 / 底部导航栏
- 批量操作：分享、下载、添加到相册、归档、删除、添加/移除收藏
- 支持两种启动模式：普通浏览、`ACTION_GET_CONTENT`/`ACTION_PICK`（为其他应用选择媒体）
- 支持 TV 遥控器操作（KEYCODE_SETTINGS/MENU/SEARCH 等）
- 可搜索书签快速应用搜索配置
- 下载进度管理

---

## 3. 搜索 (search)

可配置的搜索功能模块。

| 项目 | 说明 |
|------|------|
| Activity | `GallerySearchConfigActivity`, `GallerySearchAlbumSelectionActivity` |
| ViewModel | `GallerySearchViewModel`, `GallerySearchAlbumsViewModel`, `GallerySearchAlbumSelectionViewModel`, `GallerySearchPeopleViewModel`, `SearchBookmarkDialogViewModel` |
| UseCase | `ExportSearchBookmarksUseCase`, `ImportSearchBookmarksUseCase`, `SearchPredicates`, `TvDetector` |
| DI | `GallerySearchFeatureModule` |

**功能细节**：
- 搜索状态管理：`NoSearch` → `Configuring` → `Applied`
- 媒体类型筛选：IMAGE / VIDEO / ANIMATED / LIVE / RAW / VECTOR
- 支持按用户查询关键字、是否包含隐私内容、是否仅收藏、相册筛选、人物筛选
- **搜索书签**：创建、编辑、重命名、拖拽排序、点击应用
- 搜索书签支持导出为 JSON 文件、从 JSON 文件导入
- TV 检测：电视上使用独立搜索配置屏幕

---

## 4. 媒体查看器 (viewer)

全屏媒体查看与交互模块。

| 项目 | 说明 |
|------|------|
| Activity | `MediaViewerActivity` |
| ViewModel | `GalleryMediaViewerViewModel`, `VideoPlayerCacheViewModel` |
| UseCase | `BackgroundMediaFileDownloadManager`, `UpdateGalleryMediaAttributesUseCase`, `VideoPlayer`, `ClippingSourceFixMediaSourceFactory` |
| DI | `MediaViewerFeatureModule` |

**功能细节**：
- ViewPager2 实现滑动切换媒体
- 图片：Picasso 加载 + PhotoView 双指缩放
- 视频：ExoPlayer 播放，支持缓存
- **Live Photo**：增强型 Live Photo 查看器，对三星/苹果拍摄的 Live Photo 效果最佳
- 滑动关闭（SwipeToDismiss）
- 底部控制栏：分享、下载、收藏、取消下载
- 工具栏菜单：开始幻灯片、打开方式、Web 查看器、添加到相册、从相册移除、归档、删除、隐私设置、附近照片
- 无限滚动加载更多媒体
- 全屏切换（System UI 隐藏/显示）
- TV 方向键翻页

---

## 5. 幻灯片播放 (slideshow)

自动播放媒体的幻灯片模块。

| 项目 | 说明 |
|------|------|
| Activity | `SlideshowActivity`, `SlideshowGuideActivity` |
| ViewModel | `SlideshowViewModel` |
| DI | `SlideshowFeatureModule` |

**功能细节**：
- 5 种播放速度可选
- 自动翻页，视频播放完毕后立即切换
- 图片显示时长由速度设置控制
- 点击左侧/右侧区域手动切换
- 视频播放失败自动标记为不可播放
- 首次使用时显示使用指南页面
- 3D 翻页动画效果（DepthPageTransformer）

---

## 6. 相册 (albums)

统一的相册/文件夹/日历浏览模块。

| 项目 | 说明 |
|------|------|
| Activity | `AlbumsActivity`, `DestinationAlbumSelectionActivity` |
| ViewModel | `AlbumsViewModel`, `DestinationAlbumSelectionViewModel`, `AlbumSortResources` |
| DI | `AlbumsFeatureModule` |

**功能细节**：
- 三种相册类型：FOLDER（文件夹）、ALBUM（相册）、MONTH（月份）
- 支持搜索过滤
- 排序对话框（多种排序方式）
- 下拉刷新
- 点击相册项跳转到 `GallerySingleRepositoryActivity` 浏览该相册内容
- 目标相册选择（用于添加到相册操作）：支持搜索、单选/多选、精确匹配（新建相册）

---

## 7. 标签 (labels)

标签浏览模块。

| 项目 | 说明 |
|------|------|
| Activity | `LabelsActivity` |
| ViewModel | `LabelsViewModel` |
| DI | `LabelsFeatureModule` |

**功能细节**：
- 标签浏览界面，支持搜索过滤
- 切换显示所有标签 / 仅常用标签

---

## 8. 人物 (people)

人物选择模块。

| 项目 | 说明 |
|------|------|
| Activity | `PeopleSelectionActivity` |
| ViewModel | `PeopleSelectionViewModel` |
| DI | `PeopleFeatureModule` |

**功能细节**：
- 人物选择界面，用于搜索中的人物筛选
- 支持搜索过滤
- 返回选中的 Person ID 列表

---

## 9. 地图 (map)

基于 MapLibre GL 的地图浏览模块。

| 项目 | 说明 |
|------|------|
| Activity | `MapActivity` |
| ViewModel | `MapViewModel` |
| DI | `MapFeatureModule` |

**功能细节**：
- 基于 MapLibre GL 的地图浏览
- 显示照片地理位置 GeoJSON 数据
- 点聚合（Cluster）：小聚合直接打开查看器，大聚合筛选范围内照片
- 支持自定义地图样式 URL
- 最新照片显示在前
- 支持指定起始位置

---

## 10. 导入 (import)

从手机导入照片/视频到 PhotoPrism 的模块。

| 项目 | 说明 |
|------|------|
| Activity | `ImportActivity` |
| ViewModel | `ImportViewModel` |
| UseCase | `ParseImportIntentUseCase`, `ImportFilesUseCase` |
| Worker | `ImportFilesWorker` |
| DI | `ImportFeatureModule` |

**功能细节**：
- 通过系统分享菜单导入照片/视频
- 显示导入摘要：库地址、文件数量、总大小
- 支持选择目标相册
- 后台 WorkManager 执行上传（Expedited Work 保证执行）
- URI 读取权限授予（确保后台可访问文件）
- 文件列表写入临时 JSON 文件（绕过 WorkManager 数据大小限制）
- 导入完成后发送通知
- 动态启用/禁用（连接/断开服务器时）

---

## 11. 扩展系统 (ext)

基于离线许可证密钥的扩展子系统。

### 11.1 扩展商店

| 项目 | 说明 |
|------|------|
| Activity | `GalleryExtensionStoreActivity` |
| ViewModel | `GalleryExtensionStoreViewModel` |

在线浏览和购买可用扩展，区分已激活/未激活状态。

### 11.2 密钥激活

| 项目 | 说明 |
|------|------|
| Activity | `KeyActivationActivity` |
| Fragment | `KeyActivationInputFragment`, `KeyActivationSuccessFragment` |
| ViewModel | `KeyActivationViewModel` |
| UseCase | `ParseEnteredKeyUseCase`, `ActivateParsedKeyUseCase` |

输入并激活扩展许可证密钥，支持 Deep Link 唤起。

### 11.3 密钥续期

| 项目 | 说明 |
|------|------|
| Activity | `KeyRenewalActivity` |
| ViewModel | `KeyRenewalViewModel` |
| UseCase | `RenewEnteredKeyUseCase` |

已激活密钥的续期管理。

### 11.4 Memories 回忆

| 项目 | 说明 |
|------|------|
| View | `GalleryMemoriesListView` |
| ViewModel | `GalleryMemoriesListViewModel` |
| UseCase | `GetMemoriesUseCase`, `UpdateMemoriesUseCase`, `ScheduleDailyMemoriesUpdatesUseCase`, `CancelDailyMemoriesUpdatesUseCase` |
| Worker | `UpdateMemoriesWorker` |

每日推送「一年前的今天」等回忆内容：
- 在 Gallery 顶部嵌入回忆列表视图
- 每天 8:00 开始检查更新
- Room 数据库存储回忆数据
- 通知提醒

### 11.5 扩展偏好设置

| 项目 | 说明 |
|------|------|
| Fragment | `GalleryExtensionPreferencesFragment` |

扩展相关的设置界面。

---

## 12. 相框小部件 (widgets/photoframe)

桌面 App Widget 模块。

| 项目 | 说明 |
|------|------|
| Activity | `PhotoFrameWidgetConfigurationActivity` |
| ViewModel | `PhotoFrameWidgetConfigurationViewModel` |
| Provider | `PhotoFrameWidgetProvider` |
| UseCase | `ReloadPhotoFrameWidgetPhotoUseCase`, `UpdatePhotoFrameWidgetPhotoUseCase`, `UpdatePhotoFrameWidgetManifestComponentsUseCase` |
| Worker | `UpdatePhotoFrameWidgetWorker` |
| DI | `PhotoFrameWidgetFeatureModule` |

**功能细节**：
- 在桌面显示 PhotoPrism 照片的 App Widget
- 配置界面选择显示的照片（限制类型：IMAGE、RAW、VECTOR、LIVE）
- 支持圆角形状
- WorkManager 后台更新照片

---

## 13. 应用偏好设置 (prefs)

全局设置模块。

| 项目 | 说明 |
|------|------|
| Activity | `PreferencesActivity` |
| Fragment | `PreferencesFragment` |

**设置项**：
- 图库外观：项缩放比例
- 搜索设置
- 扩展偏好设置入口
- 搜索书签导出选项对话框

---

## 14. WebView (webview)

通用 WebView 模块，用于多种网页场景。

| 项目 | 说明 |
|------|------|
| Activity | `WebViewActivity` |
| Fragment | `WebViewFragment` |
| DI | `WebViewFeatureModule` |

**使用场景**：
- SSO 登录（Authelia、Cloudflare Access 等）
- 连接指南页面
- 客户端证书指南页面
- PhotoPrism Web 界面浏览
- 注入脚本：自动登录注入、沉浸模式注入

---

## 15. 欢迎页面 (welcome)

首次启动引导模块。

| 项目 | 说明 |
|------|------|
| Activity | `WelcomeActivity` |
| DI | `WelcomeScreenFeatureModule` |

首次启动时显示欢迎提示，用户确认后进入连接界面，之后不再显示。

---

## 功能数统计

| 模块 | Activities | ViewModels | UseCases | Workers |
|------|-----------|------------|----------|---------|
| envconnection | 1 | 1 | 2 | - |
| gallery | 3 | 10 | 12 | - |
| search | 2 | 5 | 3 | - |
| viewer | 1 | 2 | 6 | - |
| slideshow | 2 | 1 | - | - |
| albums | 2 | 3 | - | - |
| labels | 1 | 1 | - | - |
| people | 1 | 1 | - | - |
| map | 1 | 1 | - | - |
| import | 1 | 1 | 2 | 1 |
| ext | 5 | 5 | 6 | 1 |
| prefs | 1 | - | - | - |
| webview | 1 | - | 5 | - |
| welcome | 1 | - | - | - |
| widgets | 1 | 1 | 4 | 1 |
| **总计** | **24** | **32** | **40** | **3** |
