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
            description = "灵犀 AI 助手"
            copyright = "© 2026 mvpdark. All rights reserved."
            vendor = "mvpdark"

            windows {
                menuGroup = "Lingxi"
                // 应用图标（Windows .ico）
                iconFile.set(project.layout.projectDirectory.dir("resources/windows/lingxi.ico"))
                // 捆绑 JRE，无需用户预装 Java
                jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")
            }

            macOS {
                bundleID = "top.mvpdark.lingxi"
                dockName = "灵犀"
                // 应用图标（macOS .icns）
                iconFile.set(project.layout.projectDirectory.dir("resources/macos/lingxi.icns"))
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
