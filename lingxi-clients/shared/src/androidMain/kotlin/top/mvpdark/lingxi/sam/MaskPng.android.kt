package top.mvpdark.lingxi.sam

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 将二值 mask 编码为 PNG base64 字符串（Android 实现）。
 *
 * 编码规则：
 * - mask 中 >0 的像素编码为不透明白色（ARGB = 0xFFFFFFFF）
 * - mask 中 <=0 的像素编码为全透明（ARGB = 0x00000000）
 *
 * 使用 android.graphics.Bitmap + ByteArrayOutputStream 进行 PNG 编码，
 * 再用 android.util.Base64 转为字符串（NO_WRAP，不含换行）。
 *
 * @param mask 二值 mask（>0 视为前景）
 * @param width mask 宽度
 * @param height mask 高度
 * @return base64 编码的 PNG 图片（不含 `data:image/png;base64,` 前缀）；
 *         失败时返回空字符串
 */
actual fun maskToPngBase64(mask: ByteArray, width: Int, height: Int): String {
    if (mask.isEmpty() || width <= 0 || height <= 0) return ""
    require(mask.size <= width * height) {
        "mask size ${mask.size} exceeds ${width}x${height}=${width * height}"
    }
    return runCatching {
        // 1. 创建 ARGB_8888 Bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            // 2. mask[i] > 0 → 像素不透明白色，否则全透明
            val pixels = IntArray(width * height)
            for (i in mask.indices) {
                pixels[i] = if (mask[i] > 0) WHITE_ARGB else TRANSPARENT_ARGB
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            // 3. compress(PNG) → ByteArrayOutputStream
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)

            // 4. Base64.encodeToString(NO_WRAP)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }.getOrElse { "" }
}

/** 不透明白色 ARGB。 */
private const val WHITE_ARGB = 0xFFFFFFFF.toInt()

/** 全透明 ARGB。 */
private const val TRANSPARENT_ARGB = 0x00000000
