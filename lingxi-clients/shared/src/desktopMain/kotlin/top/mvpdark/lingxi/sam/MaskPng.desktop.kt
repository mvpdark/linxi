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
    require(mask.size <= width * height) {
        "mask size ${mask.size} exceeds ${width}x${height}=${width * height}"
    }

    // 1. 创建 TYPE_INT_ARGB BufferedImage
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    // 2. 填充像素：mask[i] > 0 → 0xFFFFFFFF（不透明白），否则 0x00000000（全透明）
    //    使用批量 setRGB(int, int, int, int, int[], int, int) 一次性写入整块矩形区域，
    //    比逐像素 setRGB(x, y, pixel) 快 10 倍以上。
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        pixels[i] = if (i < mask.size && mask[i] > 0) {
            0xFFFFFFFF.toInt() // 不透明白色
        } else {
            0x00000000 // 全透明
        }
    }
    image.setRGB(0, 0, width, height, pixels, 0, width)

    // 3. ImageIO 编码 PNG（用 use 包裹确保资源释放）
    return ByteArrayOutputStream().use { outputStream ->
        ImageIO.write(image, "PNG", outputStream)
        // 4. Base64 编码
        Base64.getEncoder().encodeToString(outputStream.toByteArray())
    }
}
