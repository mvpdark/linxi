package top.mvpdark.lingxi.core.util

/**
 * ISO 8601 时间戳格式化工具。
 *
 * 将后端返回的 ISO 8601 时间字符串（如 "2026-07-18T13:52:14.955045+00:00"）
 * 转换为用户友好的显示格式。
 */

/**
 * 格式化会话时间为友好显示。
 *
 * 支持以下输入格式：
 * - "2026-07-18T13:52:14.955045+00:00"（带微秒和时区）
 * - "2026-07-18T13:52:14+00:00"（带时区）
 * - "2026-07-18T13:52:14"（无时区）
 * - "2026-07-18 13:52:14"（空格分隔）
 *
 * 输出格式：
 * - 今年： "7月18日 13:52"
 * - 非今年： "2025-12-31 13:52"
 * - 解析失败： 返回原始字符串
 *
 * @param isoTimestamp ISO 8601 格式的时间字符串。
 * @return 用户友好的时间显示。
 */
fun formatSessionTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""

    return try {
        val parsed = parseIso8601(isoTimestamp) ?: return isoTimestamp
        val now = parseIso8601(getCurrentIso8601()) ?: return isoTimestamp

        val isThisYear = parsed.year == now.year
        val time = "${parsed.hour.toString().padStart(2, '0')}:${parsed.minute.toString().padStart(2, '0')}"

        if (isThisYear) {
            "${parsed.month}月${parsed.day}日 $time"
        } else {
            "${parsed.year}-${parsed.month.toString().padStart(2, '0')}-${parsed.day.toString().padStart(2, '0')} $time"
        }
    } catch (_: Exception) {
        isoTimestamp
    }
}

/**
 * 格式化消息时间戳（仅显示时分）。
 *
 * @param isoTimestamp ISO 8601 格式的时间字符串。
 * @return "HH:MM" 格式或原始字符串。
 */
fun formatMessageTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""

    return try {
        val parsed = parseIso8601(isoTimestamp) ?: return isoTimestamp
        "${parsed.hour.toString().padStart(2, '0')}:${parsed.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) {
        isoTimestamp
    }
}

/** 解析后的日期时间组件。 */
private data class DateTimeComponents(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * 解析 ISO 8601 字符串为日期时间组件。
 *
 * 兼容多种格式变体，不依赖 java.time（保证 commonMain 可用）。
 */
private fun parseIso8601(input: String): DateTimeComponents? {
    if (input.isBlank()) return null

    return try {
        // 提取日期和时间部分：取 "T" 或空格前的部分
        val normalized = input.replace(' ', 'T')
        // 去掉时区部分（Z 或 +00:00 或 +0000）
        val withoutZone = normalized
            .substringBefore("+")
            .substringBefore("Z")
            .trimEnd()
        // 去掉微秒部分
        val withoutMicros = withoutZone.substringBefore(".")

        // 格式应为 "2026-07-18T13:52:14" 或 "2026-07-18T13:52"
        val parts = withoutMicros.split("T")
        if (parts.size < 2) return null

        val dateParts = parts[0].split("-")
        if (dateParts.size < 3) return null

        val year = dateParts[0].toIntOrNull() ?: return null
        val month = dateParts[1].toIntOrNull() ?: return null
        val day = dateParts[2].toIntOrNull() ?: return null

        val timeParts = parts[1].split(":")
        val hour = timeParts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
        val second = timeParts.getOrNull(2)?.toIntOrNull() ?: 0

        DateTimeComponents(year, month, day, hour, minute, second)
    } catch (_: Exception) {
        null
    }
}

/**
 * 获取当前时间的 ISO 8601 字符串（用于年份比较）。
 * 使用各平台已实现的 currentTimeMillis。
 */
private fun getCurrentIso8601(): String {
    val millis = currentTimeMillis()
    return millisToIso8601(millis)
}

/**
 * 毫秒时间戳转 ISO 8601 字符串（简化版，用于当前时间）。
 * 不依赖 java.time，手动计算。
 */
private fun millisToIso8601(millis: Long): String {
    val totalSeconds = millis / 1000
    val epochDay = totalSeconds / 86400
    val secondsInDay = totalSeconds % 86400

    val hour = (secondsInDay / 3600).toInt()
    val minute = ((secondsInDay % 3600) / 60).toInt()
    val second = (secondsInDay % 60).toInt()

    // 从 1970-01-01 开始计算日期
    val (year, month, day) = epochDayToYmd(epochDay)

    return "%04d-%02d-%02dT%02d:%02d:%02d".format(year, month, day, hour, minute, second)
}

/**
 * 将 epoch 天数转换为年月日（支持 1970 年前后）。
 * 使用 Civil from Days 算法（Howard Hinnant）。
 */
private fun epochDayToYmd(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719468
    val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
    val doe = (z - era * 146097).toInt() // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    val mp = (5 * doy + 2) / 153 // [0, 11]
    val d = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
    val m = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
    val year = if (m <= 2) y + 1 else y
    return Triple(year.toInt(), m, d)
}
