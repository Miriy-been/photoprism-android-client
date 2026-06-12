# Gallery for PhotoPrism - Android Client 项目规则

## 项目概述

这是一个 Android 端 [PhotoPrism](https://www.photoprism.app/) 图库客户端，提供便捷的移动端照片浏览体验。它不是 PhotoPrism 官方客户端，而是由社区开发者 Radiokot 维护的开源项目。

**包名**: `ua.com.radiokot.photoprism`
**许可证**: GPLv3

**版本身份**:
| 属性 | 值 |
|------|-----|
| versionName | 1.43.0 |
| versionCode | 67 |
| minSdk / targetSdk / compileSdk | 21 / 35 / 36 |

**构建类型**: `debug` (后缀 .debug) · `release` · `releaseClone` (共存) · `releasePlay` (仅此可打 AAB)

**分发渠道**: GitHub Releases · F-Droid · Google Play（无扩展商店）

---

## 开发注意事项

- 使用 ViewBinding，非 DataBinding
- TV 兼容性（支持遥控器操作）
- F-Droid 可重现构建需特殊处理 profm 文件排序
- Google Play 构建限制：仅 `releasePlay` 可构建 AAB
- 许可证报告自动生成到 `assets/open_source_licenses.html`

---

## 当前开发状态

### 全部完成
- **UI/UX 全面升级 6 批次**（Theme → 导航栏 → 图库 → 查看器 → 搜索/相册/标签/人物 → 上传/设置）
- **上传图片功能**（`features/upload/` 含 Activity/ViewModel/布局/中文字符串）
- **扩展系统密钥自定义**（`ParseEnteredKeyUseCase` 中 `yueyueya` 硬编码密钥）

### UI 设计系统（侘寂 Wabi-Sabi）
- iOS × 侘寂融合，Material 组件体系不变
- 配色：和紙 #F6F4EF / 藍鼠 #6B7E8A / 炭灰 #3A3835 / 褪紅 #C1665B
- 禁止：深色模式、渐变、纯黑、高饱和色
- 字体：7 级（34sp → 11sp）· 间距 8dp 基准 6 档 · 圆角卡片 16dp/按钮 12dp/照片 4dp

---

## AI Agent 参考文档（编码前必须阅读）

- **[项目总规约](file:///d:/androind-project/photoprism-android-client/doc/项目总规约.md)** — 技术栈/架构/编码规范/UI/约束（核心上下文）
- **[模块全景](file:///d:/androind-project/photoprism-android-client/doc/模块全景.md)** — 模块划分/依赖关系/入口文件/API 服务
- **[复用资产清单](file:///d:/androind-project/photoprism-android-client/doc/复用资产清单.md)** — 扩展函数/工具类/基类/组件清单
- **[Agent 指令集](file:///d:/androind-project/photoprism-android-client/.trae/documents/Agent指令集.md)** — 编码前/中/后标准行为规则
- **[需求模板](file:///d:/androind-project/photoprism-android-client/.trae/documents/需求模板.md)** — 结构化功能需求模板
- **[开发工作流程](file:///d:/androind-project/photoprism-android-client/doc/开发工作流程.md)** — 标准 5 步骤流程
- **[代码审查清单](file:///d:/androind-project/photoprism-android-client/.trae/documents/代码审查清单.md)** — AI 自检清单
- **[设计规范](file:///d:/androind-project/photoprism-android-client/doc/未来规划/design_spec.md)** — 侘寂风格完整设计系统

