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
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Lingxi"
            // 版本号：优先从环境变量读取（CI 注入），本地默认 1.0.0
            packageVersion = System.getenv("LINGXI_VERSION_NAME") ?: "1.0.0"

            windows {
                menuGroup = "Lingxi"
                // 捆绑 JRE，无需用户预装 Java
                jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")
            }

            // 通用配置
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        }
    }
}
