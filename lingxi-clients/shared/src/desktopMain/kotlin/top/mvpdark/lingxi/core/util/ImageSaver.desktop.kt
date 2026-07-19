package top.mvpdark.lingxi.core.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.mvpdark.lingxi.core.network.PlatformContext

/**
 * Desktop 图片保存实现：弹出 AWT [FileDialog] 保存对话框，写入用户选择的文件。
 *
 * FileDialog 是模态对话框，`isVisible = true` 会阻塞当前线程直到用户
 * 选择文件或取消，因此整个流程放在 [Dispatchers.IO] 中执行，避免阻塞 UI。
 *
 * 用户取消对话框时返回失败，错误信息为「已取消保存」，UI 层直接以 Snackbar 展示。
 */
actual class ImageSaver actual constructor(@Suppress("UNUSED_PARAMETER") context: PlatformContext) {

    actual suspend fun saveImage(imageUrl: String, suggestedName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readBytes(imageUrl)
                require(bytes.isNotEmpty()) { "图片内容为空" }

                val target = chooseSaveFile(suggestedName) ?: error("已取消保存")
                target.writeBytes(bytes)
                "已保存：${target.absolutePath}"
            }
        }

    /**
     * 弹出保存对话框，返回用户选择的目标文件；取消时返回 null。
     *
     * 若用户输入的文件名缺少 .jpg 扩展名则自动补齐。
     */
    private fun chooseSaveFile(suggestedName: String): File? {
        val dialog = FileDialog(null as Frame?, "保存图片", FileDialog.SAVE)
        dialog.file = "${sanitizeFileName(suggestedName)}.jpg"
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        return if (dir != null && file != null) {
            File(dir, if (file.endsWith(".jpg", ignoreCase = true)) file else "$file.jpg")
        } else {
            null
        }
    }

    /**
     * 按图片来源读取字节流：data: / file:// / http(s) / 相对路径。
     */
    private fun readBytes(imageUrl: String): ByteArray {
        return when {
            imageUrl.startsWith("data:") -> decodeDataUrl(imageUrl)
            imageUrl.startsWith("file://") -> File(imageUrl.removePrefix("file://")).readBytes()
            imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> download(imageUrl)
            // 兜底：相对路径补全为完整 URL 后下载
            else -> download(UrlResolver.resolveImageUrl(imageUrl))
        }
    }

    /**
     * HttpURLConnection 下载网络图片（15s 连接超时，30s 读取超时）。
     */
    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        return try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解码 data URL（`data:image/jpeg;base64,...`）为字节流。
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val commaIdx = dataUrl.indexOf(',')
        val base64 = if (commaIdx >= 0) dataUrl.substring(commaIdx + 1) else dataUrl
        return Base64.decode(base64)
    }

    /**
     * 清洗文件名：仅保留字母、数字、下划线、连字符与中日韩文字，其余替换为下划线。
     */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fff]"), "_")
        return cleaned.ifBlank { "lingxi_image" }
    }
}
