package top.mvpdark.lingxi.core.util

/**
 * Desktop (JVM) 平台 actual 实现。
 */
actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatDouble(value: Double, decimals: Int): String {
    return String.format("%.${decimals}f", value)
}

actual fun getAppVersion(): String {
    return System.getProperty("lingxi.version") ?: "1.0.0"
}

actual fun isAutoUpdateSupported(): Boolean = false
