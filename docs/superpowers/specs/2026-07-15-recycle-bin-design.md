# Recycle Bin（回收站）功能设计规格

> 日期: 2026-07-15
> 状态: 已批准
> 项目: PhotoPrism Android Client

## 1. 概述

将当前"归档（Archive）"概念重构为"回收站"，使删除操作更符合用户直觉。
首次删除将图片放入回收站（本质是调用 PhotoPrism 的 archive API），在回收站中再次删除才是真正的永久删除。

## 2. 概念映射

| 当前行为 | 新行为 | 底层 API |
|---------|--------|----------|
| 归档（Archive） | → **删除（移入回收站）** | `POST v1/batch/photos/archive` |
| 删除（Delete） | → **彻底删除（永久）** | `POST v1/batch/photos/delete` |
| 无 | → **还原（从回收站恢复）** | `POST v1/batch/photos/restore` |

## 3. 回收站入口

- **位置**：底部导航栏"更多"（齿轮图标）BottomSheet 菜单中
- 与"同步设置""自定义导航""设置"并列
- 图标：`ic_delete` (回收站/垃圾桶图标)
- 标签："回收站"

## 4. 删除流程

### 4.1 从图库删除（批量选择模式）
```
用户选中图片 → 点击"删除"按钮
→ 弹出确认对话框： "将 X 张图片移入回收站？"
  → [移入回收站] → 调用 batchArchive API → 图片从图库消失
  → [取消] → 无操作
```

### 4.2 从查看器删除（单图模式）
```
用户在查看器 → 菜单 → "删除"
→ 弹出确认对话框： "将这张图片移入回收站？"
  → [移入回收站] → 调用 batchArchive API → 关闭查看器返回图库
  → [取消] → 无操作
```

### 4.3 确认对话框文本
- 标题：回收站
- 消息：`"将 {count} 张图片移入回收站？它们将在 30 天后自动清空。"`
- 确认按钮：`"移入回收站"`
- 取消按钮：`"取消"`

## 5. 回收站界面

### 5.1 布局
- 与现有图库相似的网格布局（使用 `SimpleGalleryMediaRepository` + `archived:true` 搜索参数）
- 顶部标题栏："回收站"，带返回按钮
- 底部操作栏（选择模式下）：
  - **还原**（`ic_restore`）— 调用 restore API
  - **彻底删除**（`ic_delete`）— 确认后调用 batchDelete
- 右上角菜单：
  - **清空回收站** — 确认后调用 batchDelete 删除所有
- 空状态提示："回收站是空的"

### 5.2 每张图片显示信息
- 缩略图（与图库一致）
- 已删除时间标记："已删除于 2026-07-14"

### 5.3 交互
- 长按进入选择模式（与图库一致）
- 支持批量选择
- 支持单个点击预览

## 6. 自动清空

### 6.1 机制
- 本地 Room 数据库记录回收站项目：`(photoUid, archivedAt timestamp)`
- App 启动时/定时检查是否有超过设定天数的项目
- 超过期限的自动调用 `batchDelete` 永久删除

### 6.2 设置项
设置页面新增"回收站"分类：

| 设置项 | 类型 | 默认值 |
|-------|------|--------|
| 自动清空回收站 | 开关 | 开启 |
| 自动清空天数 | 数字选择 | 30 天（可选 1/7/14/30/60/90） |

### 6.3 关闭自动清空
- 关闭后，回收站中的图片不会自动删除
- 用户需手动清空或单个删除

## 7. 数据管理

### 7.1 本地数据库（Room）

```kotlin
@Entity(tableName = "recycle_bin_items")
data class RecycleBinItem(
    @PrimaryKey
    val photoUid: String,
    val archivedAt: Long,     // 移入时间戳
    val thumbnailUri: String?, // 缩略图路径
    val photoTitle: String?,   // 图片标题
)
```

```kotlin
@Dao
interface RecycleBinDao {
    @Query("SELECT * FROM recycle_bin_items ORDER BY archivedAt DESC")
    fun getAll(): Flow<List<RecycleBinItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: RecycleBinItem)

    @Query("DELETE FROM recycle_bin_items WHERE photoUid IN (:uids)")
    fun deleteByUids(uids: Collection<String>)

    @Query("SELECT * FROM recycle_bin_items WHERE archivedAt < :before")
    fun getExpiredItems(before: Long): List<RecycleBinItem>

    @Query("DELETE FROM recycle_bin_items WHERE archivedAt < :before")
    fun deleteExpired(before: Long)
}
```

### 7.2 AppDatabase 迁移
- 版本 8 → 9
- 创建 `recycle_bin_items` 表

## 8. API 层变更

### 8.1 PhotoPrismPhotosService 新增

```kotlin
@POST("v1/batch/photos/restore")
fun batchRestore(
    @Body batchPhotoUids: PhotoPrismBatchPhotoUids,
): Any
```

### 8.2 SearchConfig 变更

```kotlin
data class SearchConfig(
    // ...现有字段
    val archived: Boolean = false,  // 新增
)
```

`getPhotoPrismQuery()` 方法追加 `archived:true` 当 `archived == true`。

## 9. 关键文件变更清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `api/photos/service/PhotoPrismPhotosService.kt` | 修改 | 新增 `batchRestore()` 方法 |
| `api/photos/model/PhotoPrismBatchPhotoUids.kt` | 不变 | 可复用 |
| `features/gallery/data/model/SearchConfig.kt` | 修改 | 新增 `archived` 字段 |
| `db/AppDatabase.kt` | 修改 | 版本 8→9，新增 RecycleBinItem entity+dao |
| `db/roomMigration.kt` | 修改 | 新增 MIGRATION_8_9 |
| `features/recyclebin/data/storage/RecycleBinItem.kt` | 新增 | Entity |
| `features/recyclebin/data/storage/RecycleBinDao.kt` | 新增 | DAO |
| `features/recyclebin/logic/RestoreGalleryMediaUseCase.kt` | 新增 | 还原用例 |
| `features/recyclebin/logic/AutoClearRecycleBinUseCase.kt` | 新增 | 自动清空用例 |
| `features/gallery/logic/DeleteGalleryMediaUseCase.kt` | 修改 | 改为 archive + 记录本地 |
| `features/gallery/view/model/GalleryMediaRemoteActionsViewModelDelegateImpl.kt` | 修改 | 删除 → 移入回收站流程 |
| `features/recyclebin/view/RecycleBinActivity.kt` | 新增 | 回收站界面 |
| `features/recyclebin/view/model/RecycleBinViewModel.kt` | 新增 | 回收站 VM |
| `features/gallery/view/GalleryNavigationView.kt` | 修改 | 更多菜单增加回收站入口 |
| `features/prefs/view/PreferencesFragment.kt` | 修改 | 设置页增加回收站配置 |
| `res/xml/preferences.xml` | 修改 | 新增回收站分类 |
| `res/values/strings.xml` | 修改 | 新增字符串 |
| `res/menu/` | 修改 | 新增回收站菜单 |

## 10. 边界情况与注意事项

1. **网络离线**：删除操作需要网络（调用 API），离线时提示网络不可用
2. **API 兼容性**：`batchRestore` 需要 PhotoPrism 服务器支持；若返回 404，提示服务器版本过低
3. **数据一致性**：本地记录的 `archivedAt` 与服务器归档状态可能不同步
4. **自动清空执行时机**：App 启动时 + 打开回收站时检查
5. **来自其他客户端的归档**：若用户在 PhotoPrism Web 端归档了图片，本地回收站不会记录；打开回收站时仅查询 `archived:true`