# 灵犀原生客户端

Compose Multiplatform 项目 — 一套 Kotlin 代码同时输出 Android APK 和 Windows MSI。

## 项目结构

```
lingxi-clients/
├── shared/          # 共享模块 (KMP + CMP)
│   └── src/
│       ├── commonMain/    # 95% 共享代码
│       ├── androidMain/   # Android 平台实现
│       └── desktopMain/   # Desktop 平台实现
├── androidApp/      # Android 入口
└── desktopApp/      # Desktop 入口
```

## 技术栈

- Kotlin 2.1.21 + Compose Multiplatform 1.8.2
- Ktor 3.1.3 (HTTP + WebSocket)
- Koin 4.0.4 (DI)
- Coil 3.1.0 (图片加载)
- DataStore (token 存储)
- Navigation Compose (导航)

## 构建

```bash
# Android
./gradlew :androidApp:assembleDebug

# Desktop
./gradlew :desktopApp:packageReleaseMsi
```
