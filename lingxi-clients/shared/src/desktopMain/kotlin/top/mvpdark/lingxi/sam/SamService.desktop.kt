package top.mvpdark.lingxi.sam

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.core.util.PlatformLogger
import top.mvpdark.lingxi.data.model.Bbox
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * SAM 2 分割服务 Desktop (JVM) 实现。
 *
 * 基于 onnxruntime-jvm（com.microsoft.onnxruntime:onnxruntime:1.26.0），
 * 在 JVM 上运行 EdgeTAM / SAM 2 fp16 模型，无需后端服务器。
 *
 * 模型文件结构（与 Android int8 版本相同结构，量化方式不同）：
 * - `vision_encoder_fp16.onnx` + `.onnx_data`：图像编码器，输出 image_embeddings
 * - `prompt_mask_decoder_fp16.onnx` + `.onnx_data`：提示编码器 + 掩码解码器，输出 pred_masks
 *
 * 模型从 `compose.application.resources.dir/models/edgetam/`（打包后）或
 * 开发回退目录 `desktopApp/resources/models/edgetam/` 加载。
 *
 * 推理分两阶段（与 Android 实现一致）：
 * 1. vision_encoder：`pixel_values` [1,3,1024,1024] → `image_embeddings` [1,256,64,64]
 * 2. prompt_mask_decoder：`image_embeddings` + `input_boxes` [1,N,4] + `original_sizes` [2]
 *    → `pred_masks` [1,N,1,H_low,W_low]
 *
 * 与 Android 实现的差异仅在于平台 API：
 * 1. 模型文件路径：`compose.application.resources.dir` vs Android assets
 * 2. 图片解码：`javax.imageio.ImageIO` vs `android.graphics.BitmapFactory`
 * 3. PNG 编码：`java.awt.image.BufferedImage` + `ImageIO` vs `android.graphics.Bitmap`
 * 4. Desktop 的 PlatformContext 为空占位，不使用
 *
 * 预处理复用 [SamImageProcessor]，mask 后处理复用 [MaskPostProcessor]。
 *
 * @param context 平台上下文（Desktop 为空占位，不使用）
 */
actual class SamService actual constructor(@Suppress("UNUSED_PARAMETER") context: PlatformContext) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    @Volatile private var visionSession: OrtSession? = null
    @Volatile private var decoderSession: OrtSession? = null

    @Volatile actual var isReady: Boolean = false
        private set

    // 动态发现的 IO 名称（模型导出时可能使用不同命名）
    private var visionInputName: String = "pixel_values"
    private var visionOutputName: String = "image_embeddings"
    private var decoderEmbeddingsName: String = "image_embeddings"
    private var decoderBoxesName: String = "input_boxes"
    private var decoderOrigSizeName: String? = "original_sizes"
    private var decoderReshapedSizeName: String? = null
    private var decoderOutputName: String = "pred_masks"

    /**
     * 加载 SAM 模型。
     *
     * 从资源目录加载 vision_encoder 和 prompt_mask_decoder 两个 ONNX 会话，
     * 加载完成后动态发现并打印模型 I/O 名称（调试用），将 isReady 置为 true。
     */
    actual suspend fun loadModel(onProgress: (Int, String) -> Unit) {
        if (isReady) return
        withContext(Dispatchers.Default) {
            onProgress(5, "准备模型目录")
            val modelsDir = resolveModelsDir()
            require(modelsDir.exists()) {
                "Models directory not found: ${modelsDir.absolutePath}"
            }

            // 1. 检查模型文件是否存在
            val visionOnnx = File(modelsDir, "vision_encoder_fp16.onnx")
            require(visionOnnx.exists()) {
                "Vision encoder model not found: ${visionOnnx.absolutePath}"
            }
            val decoderOnnx = File(modelsDir, "prompt_mask_decoder_fp16.onnx")
            require(decoderOnnx.exists()) {
                "Decoder model not found: ${decoderOnnx.absolutePath}"
            }

            // 2. 创建 SessionOptions（ALL_OPT + XNNPACK CPU 加速）
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            }
            // XNNPACK EP 加速 CPU 推理；部分平台/构建可能不包含 XNNPACK，静默忽略
            runCatching { opts.addXnnpack(emptyMap()) }
                .onFailure { PlatformLogger.w("SamService", "XNNPACK unavailable: ${it.message}") }

            try {
                // 3. 加载 vision encoder（用文件路径，不用字节，因 .onnx_data 需同目录）
                onProgress(30, "vision_encoder_fp16.onnx")
                visionSession = env.createSession(visionOnnx.absolutePath, opts)
                discoverVisionIO(visionSession!!)

                // 4. 加载 prompt_mask_decoder
                onProgress(70, "prompt_mask_decoder_fp16.onnx")
                decoderSession = env.createSession(decoderOnnx.absolutePath, opts)
                discoverDecoderIO(decoderSession!!)

                isReady = true
                onProgress(100, "模型加载完成")
            } catch (e: Exception) {
                PlatformLogger.e("SamService", "loadModel failed", e)
                runCatching { visionSession?.close() }
                runCatching { decoderSession?.close() }
                visionSession = null
                decoderSession = null
                throw e
            } finally {
                runCatching { opts.close() }
            }
        }
    }

    /**
     * 对图片执行分割，提取每个物体的精确轮廓与 mask。
     *
     * 流程（与 Android 实现一致）：
     * 1. ImageIO 解码图片字节 → 0xAARRGGBB IntArray
     * 2. [SamImageProcessor.preprocess] 预处理 → CHW FloatArray + 尺寸信息
     * 3. vision_encoder 推理 → image_embeddings
     * 4. prompt_mask_decoder 推理（批量 input_boxes）→ pred_masks
     * 5. [MaskPostProcessor] 后处理：二值化 → 上采样 → polygon + PNG
     */
    actual suspend fun segment(
        imageBytes: ByteArray,
        objects: List<Pair<Int, Bbox>>,
    ): SamSegmentResult {
        if (!isReady) {
            return SamSegmentResult(success = false, error = "模型未加载")
        }
        if (objects.isEmpty()) {
            return SamSegmentResult(success = true, objects = emptyList())
        }

        val vision = visionSession
            ?: return SamSegmentResult(success = false, error = "vision 会话未初始化")
        val decoder = decoderSession
            ?: return SamSegmentResult(success = false, error = "decoder 会话未初始化")

        return withContext(Dispatchers.Default) {
            // 收集所有需关闭的资源，最终逆序释放
            val resources = mutableListOf<AutoCloseable>()
            try {
                // 1. 解码图片字节 → ARGB IntArray（0xAARRGGBB，与 BufferedImage.getRGB 一致）
                val image = ImageIO.read(ByteArrayInputStream(imageBytes))
                    ?: return@withContext SamSegmentResult(
                        success = false,
                        error = "图片解码失败",
                    )
                val origW = image.width
                val origH = image.height
                val pixels = IntArray(origW * origH)
                // getRGB(int, int, int, int, int[], int, int) 是批量读取 API（整块矩形区域），
                // 非逐像素调用，性能可接受；保持不变。
                image.getRGB(0, 0, origW, origH, pixels, 0, origW)

                // 2. 预处理：SamImageProcessor.preprocess → CHW FloatArray + 尺寸信息
                val preprocessed = SamImageProcessor.preprocess(pixels, origW, origH)
                val pixelValues = preprocessed.pixelValues
                val reshapedH = preprocessed.reshapedInputSize[0]
                val reshapedW = preprocessed.reshapedInputSize[1]

                // 3. 阶段1：vision_encoder 推理
                //    创建 OnnxTensor (shape [1,3,1024,1024])
                val pixelTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(pixelValues), longArrayOf(1, 3, 1024, 1024),
                )
                resources.add(pixelTensor)

                val visionResult = vision.run(mapOf(visionInputName to pixelTensor))
                resources.add(visionResult)

                val embeddingsTensor = visionResult.get(visionOutputName)
                    .orElse(null) as? OnnxTensor
                    ?: return@withContext SamSegmentResult(
                        success = false,
                        error = "vision encoder 无输出 $visionOutputName",
                    )

                // 4. 阶段2：prompt_mask_decoder 推理（批量 input_boxes）
                val n = objects.size
                // 把归一化 bbox 转为像素坐标 [x1, y1, x2, y2]（原图坐标系）
                val boxes = FloatArray(n * 4)
                objects.forEachIndexed { i, (_, bbox) ->
                    boxes[i * 4 + 0] = bbox.x * origW
                    boxes[i * 4 + 1] = bbox.y * origH
                    boxes[i * 4 + 2] = (bbox.x + bbox.w) * origW
                    boxes[i * 4 + 3] = (bbox.y + bbox.h) * origH
                }
                // 创建 input_boxes tensor (shape [1, N, 4])
                val boxesTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(boxes), longArrayOf(1, n.toLong(), 4),
                )
                resources.add(boxesTensor)

                // 构造 decoder 输入（按模型实际需要补充 optional 输入）
                val decoderInputs = mutableMapOf<String, OnnxTensor>(
                    decoderEmbeddingsName to embeddingsTensor,
                    decoderBoxesName to boxesTensor,
                )
                // 仅当模型需要 original_sizes 时才创建（避免无谓的张量分配）
                val origSizeName = decoderOrigSizeName
                if (origSizeName != null && decoder.inputNames.contains(origSizeName)) {
                    val origSizes = longArrayOf(origH.toLong(), origW.toLong())
                    // 检测模型期望的 original_sizes shape：[2] 或 [1, 2]
                    val origSizeShape = resolveOrigSizeShape(decoder, origSizeName)
                    val origSizesTensor = OnnxTensor.createTensor(
                        env, LongBuffer.wrap(origSizes), origSizeShape,
                    )
                    resources.add(origSizesTensor)
                    decoderInputs[origSizeName] = origSizesTensor
                }
                // 部分模型还需要 reshaped_input_sizes（resize 后的 [H, W]）
                decoderReshapedSizeName?.let { name ->
                    if (decoder.inputNames.contains(name)) {
                        val reshapedSizes = longArrayOf(reshapedH.toLong(), reshapedW.toLong())
                        val reshapedTensor = OnnxTensor.createTensor(
                            env, LongBuffer.wrap(reshapedSizes), longArrayOf(2),
                        )
                        resources.add(reshapedTensor)
                        decoderInputs[name] = reshapedTensor
                    }
                }

                PlatformLogger.d("SamService", "decoder inputs: ${decoderInputs.keys}")
                val decoderResult = decoder.run(decoderInputs)
                resources.add(decoderResult)

                val predMasksTensor = decoderResult.get(decoderOutputName)
                    .orElse(null) as? OnnxTensor
                    ?: return@withContext SamSegmentResult(
                        success = false,
                        error = "decoder 无输出 $decoderOutputName",
                    )

                val shape = predMasksTensor.info.shape
                PlatformLogger.d("SamService", "pred_masks shape: ${shape.toList()}")

                // 解析维度：[1, N, (C), H, W] 或 [N, (C), H, W] 或 [N, H, W]
                val (hLow, wLow, _) = resolveMaskDims(shape)

                // 按物体逐个读取 mask，避免大块 FloatArray 分配导致 OOM
                val maskStride = hLow * wLow * resolveMaskChannelCount(shape)
                val scratch = FloatArray(maskStride)
                val maskBuffer = predMasksTensor.floatBuffer

                // 5. 后处理：逐物体提取 mask → 上采样 → 多边形 / PNG（同 Android）
                val results = mutableListOf<SamObject>()
                for (i in 0 until n) {
                    val maskSize = hLow * wLow
                    // 读取当前物体的 mask 到 scratch（按通道连续布局）
                    maskBuffer.position(i * maskStride)
                    maskBuffer.get(scratch, 0, maskStride)
                    val maskBytes = ByteArray(maskSize)
                    // 按 0 阈值二值化（logit > 0 为前景，取第 0 通道）
                    for (j in 0 until maskSize) {
                        maskBytes[j] = if (scratch[j] > 0f) 1 else 0
                    }

                    // 裁剪 pad 区域：低分辨率 mask 对应 1024×1024 padded 输入，
                    // 实际内容只占 reshapedW/1024 × reshapedH/1024 的区域
                    val lowScale = wLow.toFloat() / 1024f
                    val effW = maxOf(1, (reshapedW * lowScale).toInt().coerceAtMost(wLow))
                    val effH = maxOf(1, (reshapedH * lowScale).toInt().coerceAtMost(hLow))
                    val cropped = ByteArray(effW * effH)
                    for (y in 0 until effH) {
                        System.arraycopy(maskBytes, y * wLow, cropped, y * effW, effW)
                    }

                    // 上采样到原图尺寸
                    val upsampled = MaskPostProcessor.upsampleMask(
                        cropped, effW, effH, origW, origH,
                    )

                    // 提取多边形轮廓
                    val polygon = MaskPostProcessor.maskToPolygon(
                        upsampled, origW, origH,
                    )

                    // polygon 为空时用 PNG mask 兜底
                    val maskPngB64 = if (polygon.isNullOrEmpty()) {
                        maskToPngBase64(upsampled, origW, origH)
                    } else {
                        null
                    }

                    results.add(
                        SamObject(
                            id = objects[i].first,
                            polygon = polygon,
                            maskPngB64 = maskPngB64,
                        ),
                    )
                }

                SamSegmentResult(success = true, objects = results)
            } catch (e: Exception) {
                PlatformLogger.e("SamService", "segment failed", e)
                val errorType = e::class.simpleName ?: "Exception"
                SamSegmentResult(success = false, error = "[$errorType] ${e.message ?: "分割失败"}")
            } finally {
                // 逆序关闭：先关 decoder 结果（释放 pred_masks），
                // 再关输入张量，最后关 vision 结果（释放 embeddings）
                resources.reversed().forEach { runCatching { it.close() } }
            }
        }
    }

    /**
     * 释放模型资源。
     */
    actual fun close() {
        runCatching { visionSession?.close() }
        runCatching { decoderSession?.close() }
        visionSession = null
        decoderSession = null
        isReady = false
    }

    // ---- 内部辅助 ----

    /**
     * 解析模型文件目录。
     *
     * 优先使用 `compose.application.resources.dir`（Compose Desktop 打包后设置），
     * 回退到项目开发目录 `desktopApp/resources/`。
     */
    private fun resolveModelsDir(): File {
        // 优先：Compose Desktop 打包后设置的系统属性
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (resourcesDir != null) {
            val modelsDir = File(resourcesDir, "models/edgetam")
            if (modelsDir.exists()) return modelsDir
        }
        // 回退 1：开发时项目根目录下的 desktopApp/resources
        val devDir = File("desktopApp/resources/models/edgetam")
        if (devDir.exists()) return devDir
        // 回退 2：用户主目录下的 .lingxi/models/edgetam
        val homeDir = File(System.getProperty("user.home"), ".lingxi/models/edgetam")
        if (homeDir.exists()) return homeDir
        // 回退 3：基于当前 jar 所在路径定位模型目录（打包发布后更健壮）
        val jarDir = runCatching {
            File(SamService::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull()
        if (jarDir != null) {
            val jarModelsDir = File(jarDir, "models/edgetam")
            if (jarModelsDir.exists()) return jarModelsDir
        }
        // 最终回退到开发目录（即使不存在，让后续 require 报错给出明确路径）
        return devDir
    }

    /**
     * 发现 vision_encoder 的输入/输出名并打印（便于调试）。
     *
     * - 输入候选：pixel_values, input, inputs, x
     * - 输出候选：image_embeddings, image_embed, embeddings, output
     */
    private fun discoverVisionIO(session: OrtSession) {
        val inputs = session.inputNames
        val outputs = session.outputNames
        PlatformLogger.d("SamService", "Vision inputs=$inputs, outputs=$outputs")
        visionInputName = inputs.firstOrNull { it in VISION_INPUT_CANDIDATES }
            ?: inputs.first()
        visionOutputName = outputs.firstOrNull { it in VISION_OUTPUT_CANDIDATES }
            ?: outputs.first()
        PlatformLogger.d("SamService", "Vision resolved: input=$visionInputName, output=$visionOutputName")
    }

    /**
     * 发现 prompt_mask_decoder 的输入/输出名并打印（便于调试）。
     *
     * - embeddings 输入候选：image_embeddings, image_embed, embeddings
     * - boxes 输入候选：input_boxes, boxes, bboxes, point_coords
     * - 原图尺寸输入候选：original_sizes, orig_size, original_size
     * - reshape 尺寸输入候选（可选）：reshaped_input_sizes
     * - 输出候选：pred_masks, masks, outputs
     */
    private fun discoverDecoderIO(session: OrtSession) {
        val inputs = session.inputNames
        val outputs = session.outputNames
        PlatformLogger.d("SamService", "Decoder inputs=$inputs, outputs=$outputs")

        decoderEmbeddingsName = inputs.firstOrNull { it in DECODER_EMBED_CANDIDATES }
            ?: inputs.firstOrNull { it.contains("embed", ignoreCase = true) }
            ?: inputs.first()

        decoderBoxesName = inputs.firstOrNull { it in DECODER_BOX_CANDIDATES }
            ?: inputs.firstOrNull { it.contains("box", ignoreCase = true) }
            ?: inputs.elementAtOrNull(1)
            ?: inputs.first()

        decoderOrigSizeName = inputs.firstOrNull { it in DECODER_ORIG_SIZE_CANDIDATES }
            ?: inputs.firstOrNull { it.contains("orig", ignoreCase = true) }
            ?: inputs.firstOrNull { it.contains("size", ignoreCase = true) }
            // 不回退到 inputs.last()，避免误塞 origSizesTensor 到错误位置

        decoderReshapedSizeName = inputs.firstOrNull {
            it.contains("reshaped", ignoreCase = true)
        }

        decoderOutputName = outputs.firstOrNull { it in DECODER_OUTPUT_CANDIDATES }
            ?: outputs.firstOrNull { it.contains("mask", ignoreCase = true) }
            ?: outputs.first()

        PlatformLogger.d(
            "SamService",
            "Decoder resolved: embed=$decoderEmbeddingsName, " +
                "boxes=$decoderBoxesName, origSize=${decoderOrigSizeName ?: "N/A"}, " +
                "reshapedSize=$decoderReshapedSizeName, output=$decoderOutputName",
        )
    }

    /**
     * 解析 pred_masks 输出维度，返回 (lowH, lowW, 每物体 stride)。
     *
     * 支持的 shape：
     * - [1, N, C, H, W] → 5D
     * - [1, N, H, W] → 4D（无通道维）
     * - [N, H, W] → 3D（无 batch 和通道维）
     */
    private fun resolveMaskDims(shape: LongArray): Triple<Int, Int, Int> {
        return when (shape.size) {
            5 -> Triple(
                shape[3].toInt(),
                shape[4].toInt(),
                (shape[2] * shape[3] * shape[4]).toInt(),
            )
            4 -> Triple(
                shape[2].toInt(),
                shape[3].toInt(),
                (shape[2] * shape[3]).toInt(),
            )
            3 -> Triple(
                shape[1].toInt(),
                shape[2].toInt(),
                (shape[1] * shape[2]).toInt(),
            )
            else -> throw IllegalStateException(
                "不支持的 pred_masks 维度: ${shape.size}D, shape=${shape.toList()}",
            )
        }
    }

    /**
     * 解析模型期望的 original_sizes shape。
     * SAM 2 / EdgeTAM 不同导出版本可能期望 [2] 或 [1, 2]。
     */
    private fun resolveOrigSizeShape(decoder: OrtSession, name: String): LongArray {
        return runCatching {
            val nodeInfo = decoder.inputInfo[name] ?: return@runCatching longArrayOf(2)
            val tensorInfo = nodeInfo.info as? ai.onnxruntime.TensorInfo
                ?: return@runCatching longArrayOf(2)
            val infoShape = tensorInfo.shape
            if (infoShape != null && infoShape.size == 2 && infoShape[0] == 1L) {
                longArrayOf(1, 2)
            } else {
                longArrayOf(2)
            }
        }.getOrDefault(longArrayOf(2))
    }

    /**
     * 解析 mask 的通道数。
     */
    private fun resolveMaskChannelCount(shape: LongArray): Int {
        return when (shape.size) {
            5 -> shape[2].toInt()  // [1, N, C, H, W]
            4 -> shape[1].toInt()  // [N, C, H, W]
            else -> 1              // [N, H, W] 或 [1, N, H, W]
        }
    }

    private companion object {
        private val VISION_INPUT_CANDIDATES =
            listOf("pixel_values", "input", "inputs", "x")
        private val VISION_OUTPUT_CANDIDATES =
            listOf("image_embeddings", "image_embed", "embeddings", "output")

        private val DECODER_EMBED_CANDIDATES =
            listOf("image_embeddings", "image_embed", "embeddings")
        private val DECODER_BOX_CANDIDATES =
            listOf("input_boxes", "boxes", "bboxes", "point_coords")
        private val DECODER_ORIG_SIZE_CANDIDATES =
            listOf("original_sizes", "orig_size", "original_size")
        private val DECODER_OUTPUT_CANDIDATES =
            listOf("pred_masks", "masks", "outputs")
    }
}
