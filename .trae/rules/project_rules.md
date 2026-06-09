# Gallery for PhotoPrism - Android Client 项目规则

## 项目概述

这是一个 Android 端 [PhotoPrism](https://www.photoprism.app/) 图库客户端，提供便捷的移动端照片浏览体验。它不是 PhotoPrism 官方客户端，而是由社区开发者 Radiokot 维护的开源项目。

## 技术栈

- **语言**: Kotlin 100%
- **架构**: 传统 View/XML 布局 + ViewModel 模式（非 Jetpack Compose）
- **异步**: RxJava 3 (RxJava + RxKotlin + RxAndroid)
- **DI**: Koin (4.1.1)
- **网络**: OkHttp 5 + Retrofit 3 + Jackson
- **数据库**: Room (含 kapt 编译器)
- **图片加载**: Picasso 2.8
- **视频播放**: ExoPlayer (Media3 1.6.1)
- **列表**: FastAdapter 5.7.0
- **地图**: MapLibre GL 12.3.1
- **日志**: kotlin-logging + slf4j-handroid
- **权限**: ViewBinding + Parcelize

## 架构与组织

- **包名**: `ua.com.radiokot.photoprism`
- **构建**: 基于 Feature 的模块化组织，每个 feature 自包含 data/model/view/viewModel/logic
- **基类**: `BaseActivity`、`BaseMaterialDialogFragment`
- **Repository 模式**: 数据存储统一继承 `Repository` 基类
- **扩展系统**: 基于离线许可证密钥 (Offline License Key) 的扩展激活机制
- **持久化**: 主要基于 SharedPreferences，部分功能使用 Room 数据库

## 主要 Feature

1. **gallery** - 核心照片/视频时间线图库（网格浏览、日/月分组、快速滚动、拖拽选择）
2. **search** - 可配置搜索（含搜索书签、搜索建议、标签/人物筛选）
3. **viewer** - 媒体查看器（全屏查看、滑动切换、Live Photo 支持）
4. **slideshow** - 幻灯片播放（5 种速度）
5. **albums** - 相册管理
6. **labels** - 标签浏览
7. **people** - 人物选择与浏览
8. **map** - 地图模式查看（MapLibre）
9. **import** - 导入照片/视频到 PhotoPrism（通过分享）
10. **envconnection** - 连接 PhotoPrism 服务器（支持 mTLS、Basic Auth、SSO）
11. **ext** - 扩展系统（Memories 回忆、Photo Frame Widget 相框小部件、扩展商店）

## 支持的认证方式

- Session Cookie 登录
- mTLS（双向 TLS）
- HTTP Basic Auth
- SSO（Authelia、Cloudflare Access 等）

## 构建配置

- **minSdk**: 21 (Android 5.0+)
- **targetSdk**: 35
- **compileSdk**: 36
- **versionName**: 1.43.0
- **versionCode**: 67
- **构建类型**:
  - `debug` - 调试版 (applicationId 后缀 .debug)
  - `release` - 发布版
  - `releaseClone` - 克隆版（可与其他版本共存）
  - `releasePlay` - Google Play 专用版
- **多语言**: en, cs, de, el, es, fr, it, pl, ru, tr, uk, zh-Hans, zh-Hant

## 分发渠道

- GitHub Releases (APK 直接下载)
- F-Droid
- Google Play（功能受限，无扩展商店支持）

## 许可证

- GPLv3（作者明确反对知识产权概念，但选择 GPLv3 作为防御性工具）

## 关键依赖说明

- Material 组件锁定 1.8.0（更新需检查颜色、资源 ID、搜索栏、底部导航等兼容性）
- ExoPlayer 锁定 1.6.1（1.8.0 会导致 Live Photo 播放卡顿）
- Picasso 2.8（非 Coil/Glide）
- WorkManager 锁定 2.10.5（minSdk 21 兼容性）/ 2.9.0（RxJava 3 集成版本）
- Preference 库锁定 1.2.1（需同步 Material 覆盖）

## 开发注意事项

- 使用 ViewBinding，非 DataBinding
- Tv 兼容性（支持遥控器操作）
- F-Droid 可重现构建需要特殊处理（profm 文件排序）
- Google Play 构建限制：仅 `releasePlay` 类型可以构建 AAB
- 不推荐直接修改主分支的大型重构
- 许可证报告自动生成到 `assets/open_source_licenses.html`

## 代码风格约定

- 包名使用全小写
- Feature 内部结构: `data/` (model + storage) -> `view/` (Activity + ViewModel + Adapter) -> `logic/` (UseCase)
- 跨 Feature 共享逻辑放在 `base/` 或 `extension/` 目录
- API 定义统一放在 `api/` 目录下，按 domain 分模块（albums/photos/session 等）
