import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets — 暂时禁用，iOS 源集代码保留待 macOS 环境调试
    // 启用方法：取消下面注释，并确保 iosMain 源集用 creating 而非 getting
    // val iosArm64Target = iosArm64()
    // val iosX64Target = iosX64()
    // val iosSimulatorArm64Target = iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.json)

            // Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // Coroutines
            implementation(libs.coroutines.core)

            // Serialization
            implementation(libs.serialization.json)

            // Coil
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)

            // Navigation
            implementation(libs.navigation.compose)

            // Lifecycle
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.datastore.preferences)
            implementation(libs.onnxruntime.android)
            // rememberLauncherForActivityResult + ActivityResultContracts 需要 activity-compose
            implementation(libs.androidx.activity.compose)
            // FileProvider（APK 自动更新安装）需要 core-ktx
            implementation(libs.androidx.core.ktx)
            // EXIF 旋转规范化：与 Coil AsyncImage 显示的图片保持坐标系一致，
            // 避免 SAM2 解码（BitmapFactory）未应用 EXIF 旋转导致坐标误差
            implementation("androidx.exifinterface:exifinterface:1.4.1")
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.java)
                implementation(libs.coroutines.swing)
                implementation(compose.desktop.currentOs)
                implementation(libs.onnxruntime.jvm)
            }
        }

        // iOS 源集 — 暂时禁用（与 iOS target 一起注释）
        // val iosMain by getting {
        //     dependencies {
        //         implementation(libs.ktor.client.darwin)
        //     }
        // }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }

    // iOS framework — 暂时禁用
    // listOf(iosArm64Target, iosX64Target, iosSimulatorArm64Target).forEach { target ->
    //     target.binaries.framework {
    //         baseName = "ComposeApp"
    //         isStatic = true
    //     }
    // }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

android {
    // namespace 仅影响 R 类生成位置，与 applicationId 解耦。
    // shared 模块用 top.mvpdark.lingxi.shared，androidApp 用 com.mvpdark.lingxi，
    // 二者差异不影响功能，保持现状。
    namespace = "top.mvpdark.lingxi.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
