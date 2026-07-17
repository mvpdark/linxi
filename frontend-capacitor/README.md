# 灵犀 - Capacitor Android 端

本目录是「灵犀」的 Capacitor 7 安卓打包工程。前端不复制、不重新构建，
直接引用项目根目录的 `src/static/`（Vue 3 CDN 模式），
后端为远端 Docker 服务（见 `src/static/config.json` 的 `apiBase`）。

## 目录结构（android/ 由命令生成，见下文）

```
frontend-capacitor/
├── package.json            # @capacitor/core / cli / android 依赖与构建脚本
├── capacitor.config.ts     # appId=com.mvpdark.lingxi，appName=灵犀，webDir=../src/static
└── android/                # 【首次由 npx cap add android 生成，不在仓库中手工创建】
    ├── app/
    │   ├── build.gradle
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── assets/public/   # cap sync 把 ../src/static 复制到这里
    │       └── res/             # 应用图标、启动画面（可后续替换）
    ├── gradlew / gradlew.bat    # Gradle Wrapper（构建 APK/AAB 用）
    └── build.gradle
```

## webDir 是如何指向 src/static 的

`capacitor.config.ts` 中：

```ts
webDir: '../src/static'
```

- 以 `frontend-capacitor/` 为基准，向上一级进入项目根目录的 `src/static/`。
- 每次执行 `npx cap sync android`（或 `cap copy android`），
  Capacitor 会把该目录**整体复制**到 `android/app/src/main/assets/public/`，
  App 启动后由 WebView 通过 `https://localhost` 本地加载 `index.html`。
- 前端全部为相对路径资源 + 运行时读取 `config.json`，与 WebView 加载方式天然兼容。

## 首次初始化（生成 android/ 目录）

前置条件：Node.js 18+、JDK 17+、Android Studio（含 Android SDK Platform 34+）。

```bat
cd frontend-capacitor
npm install
npx cap add android
```

`cap add android` 只执行一次；之后前端有改动只需：

```bat
npx cap sync android
```

## 构建 APK / AAB

### 方式一：npm 脚本（推荐）

```bat
cd frontend-capacitor

npm run build:apk       # Debug APK（可直接安装测试，无需签名）
npm run build:release   # Release APK（需签名，见下文）
npm run build:aab       # Release AAB（Google Play 上架格式，需签名）
```

产物位置：

```
android/app/build/outputs/apk/debug/app-debug.apk
android/app/build/outputs/apk/release/app-release.apk
android/app/build/outputs/bundle/release/app-release.aab
```

### 方式二：Android Studio

```bat
npx cap open android
```

在 Android Studio 中选择 Build > Build Bundle(s)/APK(s)，
可图形化完成签名与构建。

### Release 签名（仅首次需要配置）

```bat
keytool -genkey -v -keystore lingxi-release.keystore -alias lingxi -keyalg RSA -keysize 2048 -validity 10000
```

在 `android/app/build.gradle` 的 `android { }` 中加入 `signingConfigs`，
或在 `android/` 下创建 `keystore.properties` 并引用（**keystore 与密码不要提交 git**）。

## 真机调试

```bat
npx cap sync android
cd android
gradlew.bat installDebug    # 通过 adb 安装到已连接设备
```

或在 Android Studio 中直接 Run。前端改动后重新 `cap sync` 即可，无需重新 add。

## 图标与启动画面

生成 `android/` 后，可用官方工具从单张素材批量生成：

```bat
npm install -D @capacitor/assets
npx capacitor-assets generate --android
```
