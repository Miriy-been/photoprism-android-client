# 上传图片功能规划

## 1. 需求分析

### 1.1 现有功能分析

当前项目已存在通过 **分享菜单** 导入文件的功能（`features/importt/`）：

| 组件 | 职责 |
|------|------|
| `ImportActivity` | 显示导入摘要，选择目标相册 |
| `ImportViewModel` | 处理导入逻辑，使用 WorkManager 后台执行 |
| `ImportFilesUseCase` | 执行实际上传（多文件并行，最多4个） |
| `ImportFilesWorker` | 后台 Worker，支持前台服务通知 |
| `PhotoPrismUploadService` | Retrofit API 接口（`uploadUserFiles` + `processUserUpload`） |

**现有上传流程**：
```
其他应用分享文件 → ImportActivity → 选择相册 → WorkManager 后台上传 → 通知结果
```

### 1.2 用户需求

用户希望新增 **从手机相册直接选择图片上传** 的功能，即：

1. 在图库主界面添加上传入口
2. 调用系统相册选择图片
3. 显示选择的图片列表
4. 支持选择目标相册
5. 上传并显示进度

### 1.3 功能对比

| 功能 | 现有分享导入 | 新增相册上传 |
|------|-------------|-------------|
| 入口 | 其他应用分享 | GalleryActivity 内按钮 |
| 文件来源 | 分享 Intent | 系统相册选择 |
| 交互流程 | 自动解析 Intent | 用户手动选择 |
| 文件预览 | 无 | 需要预览选择 |

---

## 2. 功能设计

### 2.1 核心功能

| 功能点 | 描述 |
|--------|------|
| 上传入口 | 在 GalleryActivity 添加上传按钮 |
| 图片选择 | 调用系统相册选择单张或多张图片 |
| 文件预览 | 显示已选文件列表，支持移除 |
| 相册选择 | 选择目标相册（复用现有 DestinationAlbumSelectionActivity） |
| 上传执行 | 使用现有 ImportFilesUseCase 执行上传 |
| 进度通知 | 通过 WorkManager 前台服务显示进度 |

### 2.2 界面设计

#### 2.2.1 上传入口
- 在 `GalleryActivity` 的底部导航栏或菜单中添加"上传"按钮
- 点击后打开图片选择器

#### 2.2.2 文件选择确认界面（新建）
- 显示已选图片缩略图网格
- 支持长按移除单张图片
- 显示文件数量和总大小
- 提供"选择相册"按钮
- 提供"开始上传"按钮

### 2.3 数据流

```
GalleryActivity
    ↓ 点击上传按钮
系统相册选择
    ↓ 返回选中的 URIs
UploadFilesActivity（新建）
    ↓ 用户确认并选择相册（可选）
ImportFilesUseCase（复用）
    ↓ 通过 WorkManager 后台执行
ImportFilesWorker（复用）
    ↓ 上传完成
通知结果
```

---

## 3. 架构设计

### 3.1 模块结构

```
features/
├── gallery/              # 核心图库模块（添加上传入口）
│   └── view/
│       └── GalleryActivity.kt  # 添加上传按钮和点击处理
├── importt/              # 现有导入模块（复用）
│   ├── ImportFeatureModule.kt
│   ├── logic/
│   │   ├── ImportFilesUseCase.kt    # 复用上传逻辑
│   │   ├── ImportFilesWorker.kt     # 复用后台任务
│   │   └── ParseImportIntentUseCase.kt  # 不再需要
│   ├── model/
│   │   ├── ImportableFile.kt        # 复用文件模型
│   │   └── ImportableFileRequestBody.kt
│   └── view/
│       ├── ImportActivity.kt        # 仅用于分享导入
│       ├── ImportNotificationsManager.kt
│       └── model/
│           └── ImportViewModel.kt   # 仅用于分享导入
└── upload/               # 新增上传模块
    ├── UploadFeatureModule.kt
    ├── logic/
    │   └── SelectImagesUseCase.kt   # 选择图片逻辑
    └── view/
        ├── UploadFilesActivity.kt   # 上传确认界面
        └── model/
            └── UploadFilesViewModel.kt
```

### 3.2 新增类设计

#### 3.2.1 UploadFilesActivity

| 方法 | 职责 |
|------|------|
| `onCreate()` | 初始化视图，处理选中图片 |
| `showSelectedImages()` | 显示已选图片网格 |
| `onRemoveImage()` | 移除选中的图片 |
| `onAlbumsClicked()` | 打开相册选择 |
| `onUploadClicked()` | 开始上传流程 |

#### 3.2.2 UploadFilesViewModel

| 属性 | 类型 | 职责 |
|------|------|------|
| `selectedImages` | `LiveData<List<ImportableFile>>` | 已选图片列表 |
| `selectedAlbums` | `LiveData<Set<DestinationAlbum>>` | 目标相册集合 |
| `isUploading` | `LiveData<Boolean>` | 上传状态 |

| 方法 | 职责 |
|------|------|
| `addImages(uris: List<Uri>)` | 添加选中的图片 |
| `removeImage(index: Int)` | 移除指定图片 |
| `setAlbums(albums: Set<DestinationAlbum>)` | 设置目标相册 |
| `startUpload()` | 启动上传流程 |

#### 3.2.3 SelectImagesUseCase

| 方法 | 职责 |
|------|------|
| `invoke(maxCount: Int)` | 启动图片选择器，返回选中的 URIs |

### 3.3 依赖关系

```
UploadFilesActivity
    ├── UploadFilesViewModel
    │       ├── ImportFilesUseCase (复用)
    │       ├── AlbumsRepository
    │       └── JsonObjectMapper
    └── SelectImagesUseCase
```

---

## 4. 实现计划

### 4.1 任务清单

| 序号 | 任务 | 状态 | 关联文件 |
|------|------|------|----------|
| 1 | 新建 UploadFilesActivity | pending | `features/upload/view/UploadFilesActivity.kt` |
| 2 | 新建 UploadFilesViewModel | pending | `features/upload/view/model/UploadFilesViewModel.kt` |
| 3 | 新建 SelectImagesUseCase | pending | `features/upload/logic/SelectImagesUseCase.kt` |
| 4 | 新建 UploadFeatureModule | pending | `features/upload/UploadFeatureModule.kt` |
| 5 | 更新 GalleryActivity 添加上传入口 | pending | `features/gallery/view/GalleryActivity.kt` |
| 6 | 添加上传相关布局资源 | pending | `res/layout/activity_upload_files.xml` |
| 7 | 添加上传相关字符串资源 | pending | `res/values/strings.xml` |
| 8 | 更新 AndroidManifest | pending | `AndroidManifest.xml` |

### 4.2 详细设计

#### 4.2.1 UploadFilesViewModel

```kotlin
class UploadFilesViewModel(
    private val importFilesUseCase: ImportFilesUseCase,
    private val albumsRepository: AlbumsRepository,
    private val jsonObjectMapper: JsonObjectMapper,
    application: Application,
) : AndroidViewModel(application) {
    
    val selectedImages = MutableLiveData<List<ImportableFile>>(emptyList())
    val selectedAlbums = MutableLiveData<Set<DestinationAlbum>>(emptySet())
    val isUploading = MutableLiveData(false)
    val events = PublishSubject.create<Event>()
    
    fun addImages(contentUris: List<Uri>, contentResolver: ContentResolver) {
        // 从 URIs 创建 ImportableFile 列表
    }
    
    fun removeImage(index: Int) { ... }
    
    fun setAlbums(albums: Set<DestinationAlbum>) { ... }
    
    fun startUpload() {
        // 使用 WorkManager 执行上传（参考 ImportViewModel）
    }
    
    sealed interface Event {
        object Finish
        object ShowStartedInBackgroundMessage
        class OpenAlbumSelectionForResult(val currentlySelectedAlbums: Set<DestinationAlbum>) : Event
    }
}
```

#### 4.2.2 UploadFilesActivity 布局要点

```xml
<LinearLayout>
    <!-- 标题栏 -->
    <Toolbar />
    
    <!-- 已选图片网格 -->
    <RecyclerView
        android:id="@+id/imagesGrid"
        android:layout_manager="grid"
        spanCount="4" />
    
    <!-- 摘要信息 -->
    <TextView android:text="已选择 X 张图片，共 Y MB" />
    
    <!-- 目标相册选择 -->
    <Button 
        android:text="选择相册" 
        onClick="onAlbumsClicked" />
    
    <!-- 操作按钮 -->
    <LinearLayout>
        <Button android:text="取消" onClick="finish()" />
        <Button android:text="上传" onClick="onUploadClicked" />
    </LinearLayout>
</LinearLayout>
```

#### 4.2.3 GalleryActivity 上传入口

在现有菜单或底部导航中添加上传按钮：

```kotlin
// 在 onCreateOptionsMenu 或底部导航点击处理中
fun onUploadButtonClicked() {
    startActivity(Intent(this, UploadFilesActivity::class.java))
}
```

---

## 5. API 接口

无需新增 API，复用现有 `PhotoPrismUploadService`：

| 方法 | 用途 |
|------|------|
| `uploadUserFiles()` | 上传文件到服务器 |
| `processUserUpload()` | 触发服务器端处理（索引） |

---

## 6. 数据模型

### 6.1 复用的模型

| 模型 | 来源 | 用途 |
|------|------|------|
| `ImportableFile` | `features/importt/model/` | 表示待上传的文件 |
| `DestinationAlbum` | `features/albums/data/model/` | 目标相册（现有或新建） |
| `PhotoPrismUploadOptions` | `api/upload/model/` | 上传选项（相册列表） |

### 6.2 ImportableFile 结构

```kotlin
data class ImportableFile(
    val contentUri: String,    // 文件 URI
    val displayName: String,   // 显示名称
    val mimeType: String?,     // MIME 类型
    val size: Long,            // 文件大小（字节）
)
```

---

## 7. 测试计划

### 7.1 单元测试

| 测试项 | 描述 |
|--------|------|
| UploadFilesViewModel | 测试图片添加/移除逻辑 |
| SelectImagesUseCase | 测试图片选择器调用 |
| ImportFilesUseCase | 复用现有测试 |

### 7.2 集成测试

| 测试场景 | 步骤 |
|----------|------|
| 上传入口 | 点击上传按钮打开 UploadFilesActivity |
| 图片选择 | 选择多张图片后显示在列表中 |
| 图片移除 | 长按/点击移除按钮删除图片 |
| 相册选择 | 打开 DestinationAlbumSelectionActivity 并返回 |
| 上传执行 | 点击上传按钮后后台执行并显示通知 |

---

## 8. 注意事项

### 8.1 权限处理

| 权限 | 用途 | SDK 版本 |
|------|------|----------|
| `READ_MEDIA_IMAGES` | Android 13+ 读取图片 | >= 33 |
| `READ_EXTERNAL_STORAGE` | Android 12- 读取图片 | 23-32 |
| `POST_NOTIFICATIONS` | 上传进度通知 | >= 33 |

### 8.2 性能考虑

- 图片选择限制最大数量（如 50 张）
- 使用 WorkManager 后台执行，不阻塞主线程
- 支持取消上传（通过 WorkManager cancel）

### 8.3 错误处理

- 文件读取失败时跳过并记录日志
- 网络错误时自动重试（现有 retryWithDelay）
- 上传失败时显示通知

---

## 9. 后续扩展

| 功能 | 描述 | 优先级 |
|------|------|--------|
| 拍照上传 | 直接调用相机拍照后上传 | 中 |
| 上传进度界面 | 在应用内显示实时进度，而非仅通知 | 中 |
| 上传历史 | 查看历史上传记录 | 低 |
| 后台自动上传 | 监控相册变化自动上传 | 低 |
