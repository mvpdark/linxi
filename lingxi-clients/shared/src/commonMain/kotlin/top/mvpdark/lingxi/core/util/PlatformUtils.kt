package top.mvpdark.lingxi.core.util

/**
 * 跨平台工具函数（expect 声明）。
 *
 * Android/Desktop 平台各自提供 actual 实现，避免 commonMain 直接使用 JVM API。
 */

/** 返回当前时间戳（毫秒）。 */
expect fun currentTimeMillis(): Long

/** 格式化 Double 为指定小数位数的字符串。 */
expect fun formatDouble(value: Double, decimals: Int = 2): String

/** 返回当前应用版本号（如 "1.0.35"）。 */
expect fun getAppVersion(): String

/** 当前平台是否支持应用内自动更新（Android=true, Desktop=false）。 */
expect fun isAutoUpdateSupported(): Boolean
