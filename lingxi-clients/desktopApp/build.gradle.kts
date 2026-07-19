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

            // 显式包含 java.net.http 模块 — ktor-client-java 引擎依赖 JDK 的 java.net.http.HttpClient
            // jlink 默认依赖分析无法检测到运行时通过 ServiceLoader 加载的模块
            // 缺失会导致 Win64 安装包启动失败：java/net/http/HttpClient$Version
            modules("java.net.http")

            windows {
                menuGroup = "Lingxi"
                // upgradeUuid 是 MSI 安装包的必需字段 — 用于版本升级标识
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                // 暂时不设置自定义图标 — jpackage 对 ICO 格式要求严格，
                // 当前 ICO 文件导致 "Input length = 1" 错误，先用默认图标让 MSI 打包成功
                // iconFile.set(project.layout.projectDirectory.file("resources/windows/lingxi.ico"))
                // 捆绑 JRE，无需用户预装 Java
                jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")
            }

            macOS {
                bundleID = "top.mvpdark.lingxi"
                dockName = "灵犀"
                // 应用图标（macOS .icns）— RegularFileProperty 需用 .file() 而非 .dir()
                iconFile.set(project.layout.projectDirectory.file("resources/macos/lingxi.icns"))
                // 最低系统版本 11.0（Big Sur），同时支持 Intel 和 Apple Silicon
                minimumSystemVersion = "11.0"
                jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")
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
