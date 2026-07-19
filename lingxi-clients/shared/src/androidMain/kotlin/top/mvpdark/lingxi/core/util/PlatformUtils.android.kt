package top.mvpdark.lingxi.core.util

import android.os.Build
import top.mvpdark.lingxi.di.AndroidAppContextHolder

/**
 * Android 平台 actual 实现。
 */
actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatDouble(value: Double, decimals: Int): String {
    return String.format("%.${decimals}f", value)
}

actual fun getAppVersion(): String {
    return try {
        val context = AndroidAppContextHolder.context?.androidContext
            ?: return "1.0.0"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
            info.versionName ?: "1.0.0"
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        }
    } catch (e: Exception) {
        "1.0.0"
    }
}

actual fun isAutoUpdateSupported(): Boolean = true
