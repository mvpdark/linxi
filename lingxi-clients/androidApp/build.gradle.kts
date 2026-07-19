import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
    }
}

android {
    namespace = "com.mvpdark.lingxi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mvpdark.lingxi"
        minSdk = 24
        targetSdk = 36
        // 版本号：优先从环境变量读取（CI 注入），本地默认 1
        versionCode = (System.getenv("LINGXI_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("LINGXI_VERSION_NAME") ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置：从环境变量读取（GitHub Actions 注入），本地回退到 debug
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("LINGXI_KEYSTORE_PATH")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("LINGXI_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("LINGXI_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("LINGXI_KEY_PASSWORD") ?: ""
            } else if (System.getenv("CI") != null) {
                // CI 环境下必须提供正式 keystore，否则直接失败
                throw GradleException(
                    "CI build requires LINGXI_KEYSTORE_PATH to be set. " +
                        "Please configure LINGXI_KEYSTORE_BASE64 secret in GitHub.",
                )
            } else {
                // 本地开发：使用 debug keystore
                storeFile = signingConfigs.getByName("debug").storeFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)

    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test)
}
