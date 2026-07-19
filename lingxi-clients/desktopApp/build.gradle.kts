import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "top.mvpdark.lingxi.desktop.MainKt"

        buildTypes.release.proguard {
            isEnabled = false
        }

        nativeDistributions {
            // Windows: Msi + Exe（在 Windows runner 上构建）
            // macOS: Dmg（在 macOS runner 上构建，不能交叉编译）
            // Linux 上运行 packageDmg 会自动跳过
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Lingxi"
            // 版本号：优先从环境变量读取（CI 注入），本地默认 1.0.0
            packageVersion = System.getenv("LINGXI_VERSION_NAME") ?: "1.0.0"
            // 用英文描述 — WiX Toolset 处理中文字符时会出现 MalformedInputException: Input length = 1
            description = "Lingxi AI Assistant"
            copyright = "Copyright 2026 mvpdark. All rights reserved."
            vendor = "mvpdark"

            // 显式声明 jlink 所需的全部 JDK 模块
            // Compose 插件不会自动检测（官方文档明确说明），缺失会导致运行时
            // ClassNotFoundException / "Failed to launch JVM"
            // 特别注意：java.prefs 缺失会导致 Koin 启动时 Preferences 类加载失败 → 立即崩溃
            //          jdk.crypto.ec 缺失会导致 HTTPS TLS 握手失败（ServiceLoader 延迟加载，jdeps 检测不到）
            //
            // 注意：JavaFX（javafx-web 等全景内嵌浏览器依赖）是 Maven 第三方模块，
            // 【不能】加入此列表 —— jlink 仅从 JDK 的 jmods 目录解析模块，
            // 加入会报 "module not found: javafx.web" 导致打包失败。
            // JavaFX jar 会被 jpackage 自动放入应用 classpath（app/*.jar），
            // JFXPanel / Platform.startup 方式从 classpath 运行 JavaFX 完全可行，
            // 原生库（jfxwebkit.dll 等）由 JavaFX 从 jar 中自动解压加载。
            modules(
                "java.desktop",           // Compose Desktop (Swing/AWT)、ImageIO、BufferedImage、JFileChooser
                "java.logging",           // ktor-client-logging、java.util.logging、JavaFX WebKit
                "java.management",        // onnxruntime JMX、Koin
                "java.naming",            // JNDI（日志/SSL 间接依赖）
                "java.net.http",          // ktor-client-java 引擎（HttpClient）
                "java.prefs",             // TokenStore 的 java.util.prefs.Preferences（Koin 启动时即加载）
                "jdk.crypto.cryptoki",    // HTTPS TLS — PKCS11 提供者（ServiceLoader 延迟加载）
                "jdk.crypto.ec",          // HTTPS TLS — ECC 算法（TLS 1.3 必需，ServiceLoader 延迟加载）
                "jdk.jsobject",           // JavaFX WebEngine JS 桥（netscape.javascript.JSObject/JSException，WebView 内部引用）
                "jdk.unsupported",        // sun.misc.Unsafe（底层库依赖）
            )

            windows {
                menuGroup = "Lingxi"
                // upgradeUuid 是 MSI 安装包的必需字段 — 用于版本升级标识
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                // 暂时不设置自定义图标 — jpackage 对 ICO 格式要求严格，
                // 当前 ICO 文件导致 "Input length = 1" 错误，先用默认图标让 MSI 打包成功
                // iconFile.set(project.layout.projectDirectory.file("resources/windows/lingxi.ico"))
                // 捆绑 JRE，无需用户预装 Java
                jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8")
            }

            macOS {
                bundleID = "top.mvpdark.lingxi"
                dockName = "灵犀"
                // 应用图标（macOS .icns）— RegularFileProperty 需用 .file() 而非 .dir()
                iconFile.set(project.layout.projectDirectory.file("resources/macos/lingxi.icns"))
                // 最低系统版本 11.0（Big Sur），同时支持 Intel 和 Apple Silicon
                minimumSystemVersion = "11.0"
                jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8")
                // 构建号（CFBundleVersion），与 packageVersion 分开
                packageBuildVersion = System.getenv("LINGXI_VERSION_NAME") ?: "1.0.0"
            }

            // 通用配置
            // 资源按 OS/架构自动分发：
            //   resources/common/         → 所有平台
            //   resources/macos-arm64/    → Apple Silicon 专属
            //   resources/macos-x64/      → Intel 专属
            //   resources/windows-x64/    → Windows 专属
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        }
    }
}
