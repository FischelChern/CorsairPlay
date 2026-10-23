# CorsairPlay

CorsairPlay 是面向林肯/福特车机的 CarPlay 增强版。支持有线/无线连接、导航压低音乐、微信语音修复、方向盘切歌、仪表导航与歌词投屏、桌面卡片等功能。基于 DiPlay 深度优化，主打稳定和流畅。0.3.0 版本支持桌面模式，自带状态栏和导航栏，打通原车里程/续航/油耗/档位/车速等车辆数据，可在屏幕上直接控制空调、座椅、方向盘等常用功能。

> 本项目仅供个人自用，属于实验性质，不保证稳定性，也不承诺适合所有车型。使用前请自行评估风险。

## 预览

1. CarPlay 模式：纯净画面，可隐藏原车状态栏/导航栏

![CarPlay 模式](docs/screenshots/carplay-mode.png)

2. 桌面模式：自带状态栏和导航栏

![桌面模式](docs/screenshots/desktop-mode.jpg)

3. 桌面模式：小卡片

![桌面模式小卡片](docs/screenshots/desktop-mode-cards.jpg)

4. 桌面模式：CarPlay 纯地图 + 小卡片

![桌面模式 CarPlay 纯地图和小卡片](docs/screenshots/desktop-map-cards.png)

5. 桌面模式：CarPlay 原画大卡片

![桌面模式 CarPlay 原画大卡片](docs/screenshots/desktop-carplay-large.png)

## 适配情况

- 主要面向林肯/福特车型。
- 已重点适配 Android 8.1 车机。
- 最低支持 Android 7.1。
- 安装包名：`com.corsairplay`
- 公开版本：`0.2.27`

## 主要特性

1. 支持歌词和导航信息投屏到仪表盘。
2. 支持导航播报时自动压低音乐。
3. 修复微信语音异常，Siri 与小度语音一起使用不冲突。
4. 支持随心听与 CarPlay 音乐切换使用不冲突。
5. 优化小度被唤醒时媒体音量突然变大失控的异常。
6. 支持原车“音频关闭”键关闭 CarPlay 媒体声音。
7. 针对有线连接做了多项专门优化。

## 安装

1. 下载最新 APK。
2. 在车机上允许安装未知来源应用。
3. 把 APK 安装到车机。
4. 打开 CorsairPlay，按提示连接 iPhone。
5. 基础 CarPlay 不需要 root；部分车机扩展功能可能需要额外权限或 ADB。

## 合作说明

- 桌面模式需要针对不同车型定制数据接口，可以提供个性化定制和长期维护等付费服务，有需要可联系作者（kriskwok666）。
- 懒得动手的朋友自愿打赏 30 元以上，可直接获得最新代码和日常使用咨询（kriskwok666）。

![付款二维码](docs/screenshots/contact-pay.png)

## 风险说明

- 本项目不是 Apple 官方认证产品。
- 安装包包含实验性认证资料，任何拿到 APK 的人都可能提取这些资料。
- 不保证未来 iPhone 系统升级后仍然兼容。
- 不建议在重要行程中完全依赖实验功能，建议先停车测试。

## 开源与许可

本项目基于 xcertplay 开发，主项目使用 GPL-3.0 许可证；部分界面资料来自 DiAuto，使用 AGPL-3.0 许可证。

分发修改后的 APK 时，请保留原许可证、版权说明和对应源代码，不要删除原有署名。中文说明见 LICENSE-NOTICE.zh-CN.md。
