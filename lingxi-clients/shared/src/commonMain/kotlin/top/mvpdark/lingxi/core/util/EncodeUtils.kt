package top.mvpdark.lingxi.core.util

/**
 * 编码工具（纯 Kotlin 实现，跨平台可用）。
 *
 * 提供 Base64 编码与 data URL 转换，用于将本地选择的图片字节流
 * 转换为 Coil3 可直接显示、可随消息发送的 data URL。
 */
object EncodeUtils {

    private val BASE64_TABLE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    /**
     * 将字节流编码为 Base64 字符串。
     *
     * 纯 Kotlin 实现，不依赖平台 API，确保 commonMain 跨平台行为统一。
     */
    fun encodeBase64(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            sb.append(BASE64_TABLE[b0 ushr 2])
            sb.append(BASE64_TABLE[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)])
            sb.append(if (b1 >= 0) BASE64_TABLE[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) BASE64_TABLE[b2 and 0x3F] else '=')

            i += 3
        }
        return sb.toString()
    }

    /**
     * 将图片字节流转换为 data URL。
     *
     * @param bytes 图片字节流。
     * @param mime MIME 类型，默认 `image/jpeg`。
     * @return 形如 `data:image/jpeg;base64,...` 的字符串，可直接作为 Coil3 model 或随消息发送。
     */
    fun bytesToDataUrl(bytes: ByteArray, mime: String = "image/jpeg"): String {
        return "data:$mime;base64," + encodeBase64(bytes)
    }
}
