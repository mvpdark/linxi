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
            // 全景查看器：WebViewAssetLoader 以 https 同源 URL 提供本地缓存文件，
            // 绕开 file:// 下 XHR/Blob 在 Android WebView 的兼容性问题
            implementation(libs.androidx.webkit)
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.java)
                implementation(libs.coroutines.swing)
                implementation(compose.desktop.currentOs)
                implementation(libs.onnxruntime.jvm)

                // JCEF（Java Chromium Embedded Framework）：内嵌 Chromium 浏览器，
                // 支持 WebGL 渲染（Pannellum 360° 全景查看器必需）。
                // 替代旧 JavaFX WebView 方案 —— 官方 OpenJFX WebView 不支持 WebGL（JDK-8089881）。
                //
                // jcefgithub 是 JCEF 的 Maven 引导包装器：自动检测平台、从 classpath 提取
                // 原生库（jcef-natives-* jar）、初始化 CefApp。配合平台对应的 jcef-natives
                // 构件，打包后无需运行时下载。
                //
                // 平台分类器在配置期按构建机 OS/架构确定（与旧 JavaFX 方案同样策略），
                // CI 在各平台 runner 上构建时会自动解析到对应平台的 natives 构件。
                val osName = System.getProperty("os.name").lowercase()
                val osArch = System.getProperty("os.arch").lowercase()
                val jcefWrapperVersion = "146.0.10.1"
                val jcefNativesVersion =
                    "jcef-65f9d7b+cef-146.0.10+g8219561+chromium-146.0.7680.179"
                implementation("io.github.trethore:jcefgithub:$jcefWrapperVersion")
                // jcefgithub 支持的平台：linux-amd64/arm64, windows-amd64/arm64, macosx-amd64/arm64
                // CI: windows-latest=x64, macos-latest=Apple Silicon(aarch64)
                val jcefNativesArtifact = when {
                    osName.contains("win") ->
                        if (osArch == "aarch64") "jcef-natives-windows-arm64" else "jcef-natives-windows-amd64"
                    osName.contains("mac") ->
                        if (osArch == "aarch64") "jcef-natives-macosx-arm64" else "jcef-natives-macosx-amd64"
                    else ->
                        if (osArch == "aarch64") "jcef-natives-linux-arm64" else "jcef-natives-linux-amd64"
                }
                implementation("io.github.trethore:$jcefNativesArtifact:$jcefNativesVersion")
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
