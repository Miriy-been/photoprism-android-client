# 任务计划：UI/UX 全面升级

## 目标

将 PhotoPrism Android 客户端从传统 Material Design 1 升级为 iOS × 侘寂风格融合体验，保持 Material 组件体系不变，注入日系克制的质感。

## 批次

### 批次 1：全局 Theme & Style 基础
- 状态：completed
- 任务：
  - 创建全局颜色资源（`colors.xml`）替代现有颜色
  - 创建全局 Theme/Style（`themes.xml`, `styles.xml`）
  - 统一字体样式（`styles.xml` 中的 TextAppearance）
  - 配置圆角、间距变量（`dimens.xml`）
- 验证：所有页面颜色自动更新，无需单独改

### 批次 2：底部导航栏重构
- 状态：completed
- 任务：
  - 重构 `GalleryNavigationView`
  - 更新导航图标为 iOS 风格
  - 选中态胶囊/高亮效果
  - 底部栏高度、分割线
- 验证：4 个标签切换流畅，选中态正确

### 批次 3：图库主界面重设计
- 状态：completed
- 任务：
  - 3 列网格布局
  - 分组标题样式（藍鼠色 22sp）
  - 工具栏样式更新
  - 快速滚动滑块微调
  - 空状态设计
- 验证：照片浏览体验提升

### 批次 4：媒体查看器重设计
- 状态：completed
- 任务：
  - 消灭纯黑 #000000（暖暗色 #1A1816 替代）
  - 图标色 #000000 → 暖灰 #8D8882（13 个矢量图标）
  - 文字样式 Material3 → Wabi（Callout/Caption1/Caption2）
  - 暗色主题背景暖化（保留功能暗色背景，非"深色模式"）
- 验证：build successful

### 批次 5：中频页面（搜索、相册、标签、人物）
- 状态：completed
- 任务：
  - 搜索配置页面样式更新（10 个布局文件）
  - 相册列表/选择页样式更新
  - 标签浏览页 list_item_collection 样式
  - 人物选择页样式
  - 卡片圆角 8dp → 16dp
- 验证：build successful

### 批次 6：低频页面（上传、设置、其余）
- 状态：completed
- 任务：
  - 上传确认页（26 个文件涵盖 upload / envconnection / ext / slideshow / widget 等）
  - 设置页（activity_preferences 已符合规范）
  - 欢迎页、扩展密钥、幻灯片引导等
- 验证：build successful

## 错误记录

| 错误 | 批次 | 解决 | 状态 |
|------|------|------|------|
| — | — | — | — |

## 决策记录

| 决策 | 理由 | 批次 |
|------|------|------|
| 禁用深色模式 | 用户偏好浅色 | 1 |
| 禁用渐变 | 克制美学原则 | 1 |
| 禁用纯黑 #000 | 侘寂温润感 | 1 |
| 日系蓝鼠配色 | 低调内敛、经典不过时 | 1 |
| 不使用毛玻璃 | Android 性能 + 侘寂克制 | — |
| 保留 Material 组件体系 | 改动可控，维护性好 | 全局 |

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-10 | 初始计划 |
| 2026-06-10 | 批次 1 完成：colors.xml / themes.xml / styles.xml / dimens.xml / wabi_card_background.xml |
| 2026-06-10 | 批次 2 完成：手机端 BottomNavigationView（4 tab）、平板端 NavigationRail + 侘寂分隔线 |
