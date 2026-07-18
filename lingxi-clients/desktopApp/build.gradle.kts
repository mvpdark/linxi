import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop")

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        common.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "top.mvpdark.lingxi.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Lingxi"
            packageVersion = "1.0.0"

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
