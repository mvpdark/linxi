package top.mvpdark.lingxi.sam

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Mask → PNG Base64 编码 iOS 实现：使用 Skia（Skiko）进行 PNG 编码。
 *
 * 实现流程：
 * 1. 将 mask（每像素 1 byte，>0 为前景）转为 RGBA_8888 像素数组
 * 2. 用 Skia [Bitmap] + [Image] 编码为 PNG 字节
 * 3. Base64 编码返回
 *
 * 与 Desktop 端区别：
 * - Desktop 使用 java.awt.image.BufferedImage + javax.imageio.ImageIO
 * - iOS 使用 org.jetbrains.skia.Bitmap/Image（Skiko）
 *
 * Base64 使用 kotlin.io.encoding.Base64（Kotlin Multiplatform 标准库），
 * 避免 java.util.Base64（JVM-only）依赖。
 *
 * @param mask 掩码数组（每像素 1 byte，>0 表示前景/选中区域）
 * @param width 图像宽度
 * @param height 图像高度
 * @return PNG 编码后的 Base64 字符串；输入无效时返回空字符串
 */
@OptIn(ExperimentalEncodingApi::class)
actual fun maskToPngBase64(mask: ByteArray, width: Int, height: Int): String {
    if (mask.isEmpty() || width <= 0 || height <= 0) return ""
    require(mask.size <= width * height) {
        "mask size (${mask.size}) must be <= width*height ($width*$height)"
    }

    // 1. 构建 RGBA_8888 像素数组（前景=白色不透明，背景=透明）
    val rgbaPixels = ByteArray(width * height * 4)
    for (i in mask.indices) {
        val offset = i * 4
        if (mask[i] > 0) {
            // 白色不透明
            rgbaPixels[offset] = -1      // R = 0xFF
            rgbaPixels[offset + 1] = -1  // G = 0xFF
            rgbaPixels[offset + 2] = -1  // B = 0xFF
            rgbaPixels[offset + 3] = -1  // A = 0xFF
        } else {
            // 透明黑
            rgbaPixels[offset] = 0
            rgbaPixels[offset + 1] = 0
            rgbaPixels[offset + 2] = 0
            rgbaPixels[offset + 3] = 0
        }
    }

    // 2. 用 Skia 编码为 PNG
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    val bitmap = Bitmap()
    try {
        bitmap.allocPixels(info)
        bitmap.installPixels(info, rgbaPixels, info.minRowBytes)

        val image = Image.makeFromBitmap(bitmap)
        try {
            val encodedData: Data? = image.encodeToData(EncodedImageFormat.PNG, 100)
            if (encodedData == null) return ""

            val pngBytes = encodedData.bytes
            encodedData.close()

            // 3. Base64 编码
            return Base64.encode(pngBytes)
        } finally {
            image.close()
        }
    } finally {
        bitmap.close()
    }
}
