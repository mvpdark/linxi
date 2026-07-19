package top.mvpdark.lingxi.sam

import kotlin.math.floor

/**
 * SAM 图像预处理结果。
 *
 * 对应 transformers.js SAMImageProcessor 的输出：
 * - [pixelValues]: CHW 格式归一化浮点数据，长度 = 3 * 1024 * 1024
 * - [reshapedInputSize]: 长边 resize 到 1024 后的尺寸 [height, width]（pad 之前）
 * - [originalSize]: 原图尺寸 [height, width]
 *
 * 后处理时需用这两个尺寸将 mask 从 1024×1024 裁剪到 reshapedInputSize，
 * 再 resize 回 originalSize。
 */
data class PreprocessResult(
    val pixelValues: FloatArray,
    val reshapedInputSize: IntArray,
    val originalSize: IntArray,
)

/**
 * SAM 图像预处理器（纯 Kotlin 实现）。
 *
 * 移植自 transformers.js SAMImageProcessor 的预处理逻辑：
 * 1. 长边 resize 到 1024（双线性插值，保持宽高比，half-pixel center，align_corners=False）
 * 2. 右下 pad 到 1024×1024（补 0）
 * 3. 归一化：mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]
 * 4. HWC 转 CHW
 *
 * 输入像素格式为 0xAARRGGBB（与 Android Bitmap.getPixels / Java BufferedImage.getRGB 一致）。
 */
object SamImageProcessor {

    private const val TARGET_SIZE = 1024
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * 预处理 RGBA 图像为 SAM 模型输入张量。
     *
     * @param rgba 原图 RGBA 像素（每像素 0xAARRGGBB）
     * @param width 原图宽度
     * @param height 原图高度
     * @return 预处理结果，包含 CHW FloatArray、resize 后尺寸、原图尺寸
     */
    fun preprocess(rgba: IntArray, width: Int, height: Int): PreprocessResult {
        require(width > 0 && height > 0) { "图片尺寸必须为正: $width x $height" }
        require(rgba.size >= width * height) { "像素数据不足: ${rgba.size} < ${width * height}" }

        // 1. 长边 resize 到 1024（保持宽高比）
        val scale = TARGET_SIZE.toFloat() / maxOf(width, height)
        val newW = minOf(TARGET_SIZE, maxOf(1, (width * scale).toInt()))
        val newH = minOf(TARGET_SIZE, maxOf(1, (height * scale).toInt()))

        // 2. pad 到 1024×1024（右下补 0）+ 归一化 + HWC→CHW
        val pixelValues = FloatArray(3 * TARGET_SIZE * TARGET_SIZE)
        // 预填充 pad 区域为归一化后的零值 (0 - mean) / std
        // SAM 预处理器是先 pad(raw 0) 再归一化，pad 区域应为 (0/255 - mean) / std
        for (c in 0 until 3) {
            val padVal = (0f - MEAN[c]) / STD[c]
            for (i in 0 until TARGET_SIZE * TARGET_SIZE) {
                pixelValues[c * TARGET_SIZE * TARGET_SIZE + i] = padVal
            }
        }
        val channelStride = TARGET_SIZE * TARGET_SIZE
        // 使用 1/scale 作为反向映射比例，避免 newW/newH 整数截断引入的累积误差；
        // 数学上 width/newW ≈ 1/scale（scale = TARGET_SIZE / maxOf(width, height)）。
        val xRatio = 1f / scale
        val yRatio = 1f / scale

        for (y in 0 until newH) {
            // half-pixel center 双线性（align_corners=False）：src = (dst + 0.5) * ratio - 0.5
            val sy = (y + 0.5f) * yRatio - 0.5f
            val y0 = floor(sy).toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceIn(0, height - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)

            for (x in 0 until newW) {
                val sx = (x + 0.5f) * xRatio - 0.5f
                val x0 = floor(sx).toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceIn(0, width - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)

                // 双线性插值取 4 个邻域像素
                val p00 = rgba[y0 * width + x0]
                val p01 = rgba[y0 * width + x1]
                val p10 = rgba[y1 * width + x0]
                val p11 = rgba[y1 * width + x1]

                // 提取 R/G/B 并双线性插值
                val r = interpolateChannel(p00, p01, p10, p11, fx, fy, 16)
                val g = interpolateChannel(p00, p01, p10, p11, fx, fy, 8)
                val b = interpolateChannel(p00, p01, p10, p11, fx, fy, 0)

                // 归一化到 0-1 + 标准化 (x - mean) / std
                val rN = (r / 255f - MEAN[0]) / STD[0]
                val gN = (g / 255f - MEAN[1]) / STD[1]
                val bN = (b / 255f - MEAN[2]) / STD[2]

                // CHW: [c][y][x]
                val pos = y * TARGET_SIZE + x
                pixelValues[pos] = rN
                pixelValues[channelStride + pos] = gN
                pixelValues[2 * channelStride + pos] = bN
            }
        }

        return PreprocessResult(
            pixelValues = pixelValues,
            reshapedInputSize = intArrayOf(newH, newW),
            originalSize = intArrayOf(height, width),
        )
    }

    /**
     * 双线性插值提取单个通道。
     *
     * @param shift 通道位移位：R=16, G=8, B=0
     */
    private fun interpolateChannel(
        p00: Int, p01: Int, p10: Int, p11: Int,
        fx: Float, fy: Float, shift: Int,
    ): Float {
        val v00 = ((p00 shr shift) and 0xFF).toFloat()
        val v01 = ((p01 shr shift) and 0xFF).toFloat()
        val v10 = ((p10 shr shift) and 0xFF).toFloat()
        val v11 = ((p11 shr shift) and 0xFF).toFloat()
        val top = v00 + (v01 - v00) * fx
        val bottom = v10 + (v11 - v10) * fx
        return top + (bottom - top) * fy
    }
}
