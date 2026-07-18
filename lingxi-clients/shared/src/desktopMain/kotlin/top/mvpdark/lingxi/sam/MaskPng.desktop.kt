package top.mvpdark.lingxi.sam

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 将二值 mask 编码为 PNG base64 字符串（Desktop/JVM 实现）。
 *
 * 使用 [BufferedImage]（TYPE_INT_ARGB）+ [ImageIO] 编码 PNG：
 * - mask 中 >0 的像素 → 不透明白色（0xFFFFFFFF）
 * - mask 中 <=0 的像素 → 全透明（0x00000000）
 *
 * 与 Android 实现的差异仅在于 PNG 编码 API：
 * - Desktop: BufferedImage + ImageIO.write
 * - Android: Bitmap + Bitmap.compress
 *
 * @param mask 二值 mask（>0 视为前景）
 * @param width mask 宽度
 * @param height mask 高度
 * @return base64 编码的 PNG 图片（不含 `data:image/png;base64,` 前缀）
 */
actual fun maskToPngBase64(mask: ByteArray, width: Int, height: Int): String {
    if (mask.isEmpty() || width <= 0 || height <= 0) {
        return ""
    }

    // 1. 创建 TYPE_INT_ARGB BufferedImage
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    // 2. 填充像素：mask[i] > 0 → 0xFFFFFFFF（不透明白），否则 0x00000000（全透明）
    for (y in 0 until height) {
        for (x in 0 until width) {
            val idx = y * width + x
            val pixel = if (idx < mask.size && mask[idx] > 0) {
                0xFFFFFFFF.toInt() // 不透明白色
            } else {
                0x00000000 // 全透明
            }
            image.setRGB(x, y, pixel)
        }
    }

    // 3. ImageIO 编码 PNG
    val outputStream = ByteArrayOutputStream()
    ImageIO.write(image, "PNG", outputStream)

    // 4. Base64 编码
    return Base64.getEncoder().encodeToString(outputStream.toByteArray())
}
