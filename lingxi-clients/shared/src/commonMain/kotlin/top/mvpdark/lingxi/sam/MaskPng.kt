package top.mvpdark.lingxi.sam

/**
 * 将二值 mask 编码为 PNG base64 字符串（平台相关实现）。
 *
 * 编码规则：
 * - mask 中 >0 的像素编码为不透明白色（RGBA = 255, 255, 255, 255）
 * - mask 中 <=0 的像素编码为全透明（RGBA = 0, 0, 0, 0）
 *
 * 对应前端 `sam-client.js` 的 `maskToPngB64`。前端使用 Canvas 编码，
 * 各平台 actual 实现可使用平台原生 PNG 编码能力：
 * - Android: android.graphics.Bitmap + ByteArrayOutputStream
 * - Desktop: java.awt.image.BufferedImage + ImageIO
 *
 * @param mask 二值 mask（>0 视为前景）
 * @param width mask 宽度
 * @param height mask 高度
 * @return base64 编码的 PNG 图片（不含 `data:image/png;base64,` 前缀）
 */
expect fun maskToPngBase64(mask: ByteArray, width: Int, height: Int): String
