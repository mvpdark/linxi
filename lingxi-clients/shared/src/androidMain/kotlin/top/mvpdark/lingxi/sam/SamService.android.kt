package top.mvpdark.lingxi.sam

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.data.model.Bbox
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * SAM 2 分割服务的 Android 实现。
 *
 * 基于 onnxruntime-android（com.microsoft.onnxruntime:onnxruntime-android:1.26.0）
 * 在端侧本地运行 EdgeTAM / SAM 2 模型，无需后端服务器。
 *
 * 模型文件结构：
 * - `vision_encoder_int8.onnx` + `.onnx_data`：图像编码器，输出 image_embeddings
 * - `prompt_mask_decoder_int8.onnx` + `.onnx_data`：提示编码器 + 掩码解码器，输出 pred_masks
 *
 * 模型从 `assets/models/edgetam/` 首次复制到 `filesDir/models/edgetam/`，
 * 然后用**文件路径**加载 OrtSession（必须用路径，因为 `.onnx_data` 外部数据
 * 需与 `.onnx` 同目录，用字节加载会丢失外部数据）。
 *
 * 推理分两阶段：
 * 1. vision_encoder：`pixel_values` [1,3,1024,1024] → `image_embeddings` [1,256,64,64]
 * 2. prompt_mask_decoder：`image_embeddings` + `input_boxes` [1,N,4] + `original_sizes` [2]
 *    → `pred_masks` [1,N,1,H_low,W_low]
 *
 * 预处理复用 [SamImageProcessor]，mask 后处理复用 [MaskPostProcessor]。
 */
actual class SamService actual constructor(private val context: PlatformContext) {

    private val env = OrtEnvironment.getEnvironment()
    @Volatile private var visionSession: OrtSession? = null
    @Volatile private var decoderSession: OrtSession? = null

    @Volatile actual var isReady: Boolean = false
        private set

    // 动态发现的 IO 名称（模型导出时可能使用不同命名）
    private var visionInputName: String = "pixel_values"
    private var visionOutputName: String = "image_embeddings"
    private var decoderEmbeddingsName: String = "image_embeddings"
    private var decoderBoxesName: String = "input_boxes"
    private var decoderOrigSizeName: String = "original_sizes"
    private var decoderReshapedSizeName: String? = null
    private var decoderOutputName: String = "pred_masks"

    actual suspend fun loadModel(onProgress: (Int, String) -> Unit) {
        if (isReady) return
        val ctx = context.androidContext
        val modelDir = File(ctx.filesDir, "models/edgetam")

        withContext(Dispatchers.Default) {
            onProgress(5, "准备模型目录")
            modelDir.mkdirs()

            // 1. 从 assets 复制模型文件（首次）
            val visionOnnx = copyAssetIfNeeded(
                "vision_encoder_int8.onnx", modelDir, onProgress, 10, 25,
            )
            copyAssetIfNeeded(
                "vision_encoder_int8.onnx_data", modelDir, onProgress, 25, 40,
            )
            val decoderOnnx = copyAssetIfNeeded(
                "prompt_mask_decoder_int8.onnx", modelDir, onProgress, 40, 55,
            )
            copyAssetIfNeeded(
                "prompt_mask_decoder_int8.onnx_data", modelDir, onProgress, 55, 70,
            )

            // 2. 创建 SessionOptions
            val opts = createSessionOptions()
            try {
                // 3. 加载 vision encoder（用文件路径，不用字节）
                onProgress(75, "加载 vision_encoder")
                visionSession = env.createSession(visionOnnx.absolutePath, opts)
                discoverVisionIO(visionSession!!)

                // 4. 加载 prompt_mask_decoder
                onProgress(90, "加载 prompt_mask_decoder")
                decoderSession = env.createSession(decoderOnnx.absolutePath, opts)
                discoverDecoderIO(decoderSession!!)

                isReady = true
                onProgress(100, "模型加载完成")
            } catch (e: Exception) {
                Log.e(TAG, "loadModel failed", e)
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
                // 1. 解码图片字节 → ARGB IntArray（0xAARRGGBB 格式）
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: return@withContext SamSegmentResult(
                        success = false,
                        error = "图片解码失败",
                    )
                val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    bitmap.recycle()
                    converted ?: return@withContext SamSegmentResult(
                        success = false,
                        error = "图片格式转换失败",
                    )
                }
                val origW = argbBitmap.width
                val origH = argbBitmap.height
                val pixels = IntArray(origW * origH)
                argbBitmap.getPixels(pixels, 0, origW, 0, 0, origW, origH)
                argbBitmap.recycle()

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

                // 4. 阶段2：prompt_encoder_mask_decoder 推理
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

                // 创建 original_sizes tensor (shape [2]，int64)
                // 注意：部分模型可能期望 [1, 2]，如遇形状不匹配请调整此处
                val origSizes = longArrayOf(origH.toLong(), origW.toLong())
                // 检测模型期望的 original_sizes shape：[2] 或 [1, 2]
                val origSizeShape = resolveOrigSizeShape(decoder, decoderOrigSizeName)
                val origSizesTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(origSizes), origSizeShape,
                )
                resources.add(origSizesTensor)

                // 构造 decoder 输入（按模型实际需要补充 optional 输入）
                val decoderInputs = mutableMapOf<String, OnnxTensor>(
                    decoderEmbeddingsName to embeddingsTensor,
                    decoderBoxesName to boxesTensor,
                )
                if (decoder.inputNames.contains(decoderOrigSizeName)) {
                    decoderInputs[decoderOrigSizeName] = origSizesTensor
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

                Log.d(TAG, "decoder inputs: ${decoderInputs.keys}")
                val decoderResult = decoder.run(decoderInputs)
                resources.add(decoderResult)

                val predMasksTensor = decoderResult.get(decoderOutputName)
                    .orElse(null) as? OnnxTensor
                    ?: return@withContext SamSegmentResult(
                        success = false,
                        error = "decoder 无输出 $decoderOutputName",
                    )

                val shape = predMasksTensor.info.shape
                Log.d(TAG, "pred_masks shape: ${shape.toList()}")

                // 解析维度：[1, N, (C), H, W] 或 [N, (C), H, W] 或 [N, H, W]
                val (hLow, wLow, _) = resolveMaskDims(shape)

                // 按物体逐个读取 mask，避免大块 FloatArray 分配导致 OOM
                val maskStride = hLow * wLow * resolveMaskChannelCount(shape)
                val scratch = FloatArray(maskStride)
                val maskBuffer = predMasksTensor.floatBuffer

                // 5. 后处理：逐物体提取 mask → 上采样 → 多边形 / PNG
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

                    // 上采样到原图尺寸
                    val upsampled = MaskPostProcessor.upsampleMask(
                        maskBytes, wLow, hLow, origW, origH,
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
                Log.e(TAG, "segment failed", e)
                SamSegmentResult(success = false, error = e.message ?: "分割失败")
            } finally {
                // 逆序关闭：先关 decoder 结果（释放 pred_masks），
                // 再关输入张量，最后关 vision 结果（释放 embeddings）
                resources.reversed().forEach { runCatching { it.close() } }
            }
        }
    }

    actual fun close() {
        runCatching { visionSession?.close() }
        runCatching { decoderSession?.close() }
        visionSession = null
        decoderSession = null
        isReady = false
    }

    // ---- 内部辅助 ----

    /**
     * 创建 ORT SessionOptions。
     *
     * - API 27+（Android 8.1+）优先使用 NNAPI 加速
     * - NNAPI 不可用时回退 XNNPACK
     * - API 26 及以下直接使用 XNNPACK
     * - 全部失败则使用默认 CPU EP
     */
    private fun createSessionOptions(): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nnapiOk = runCatching { opts.addNnapi() }
                .onFailure { Log.w(TAG, "NNAPI 不可用，回退 XNNPACK: ${it.message}") }
                .isSuccess
            if (!nnapiOk) {
                runCatching { opts.addXnnpack(emptyMap()) }
                    .onFailure { Log.w(TAG, "XNNPACK 不可用: ${it.message}") }
            }
        } else {
            runCatching { opts.addXnnpack(emptyMap()) }
                .onFailure { Log.w(TAG, "XNNPACK 不可用: ${it.message}") }
        }
        return opts
    }

    /**
     * 从 assets 复制单个模型文件到目标目录（已存在则跳过）。
     */
    private fun copyAssetIfNeeded(
        assetName: String,
        destDir: File,
        onProgress: (Int, String) -> Unit,
        progressStart: Int,
        progressEnd: Int,
    ): File {
        val destFile = File(destDir, assetName)
        if (destFile.exists() && destFile.length() > 0) {
            onProgress(progressEnd, "$assetName 已存在")
            return destFile
        }
        onProgress(progressStart, "复制 $assetName")
        destFile.parentFile?.mkdirs()
        val assetPath = "models/edgetam/$assetName"
        context.androidContext.assets.open(assetPath).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        onProgress(progressEnd, "$assetName 复制完成")
        return destFile
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
        Log.d(TAG, "Vision inputs=$inputs, outputs=$outputs")
        visionInputName = inputs.firstOrNull { it in VISION_INPUT_CANDIDATES }
            ?: inputs.first()
        visionOutputName = outputs.firstOrNull { it in VISION_OUTPUT_CANDIDATES }
            ?: outputs.first()
        Log.d(TAG, "Vision resolved: input=$visionInputName, output=$visionOutputName")
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
        Log.d(TAG, "Decoder inputs=$inputs, outputs=$outputs")

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
            ?: inputs.last()

        decoderReshapedSizeName = inputs.firstOrNull {
            it.contains("reshaped", ignoreCase = true)
        }

        decoderOutputName = outputs.firstOrNull { it in DECODER_OUTPUT_CANDIDATES }
            ?: outputs.firstOrNull { it.contains("mask", ignoreCase = true) }
            ?: outputs.first()

        Log.d(
            TAG,
            "Decoder resolved: embed=$decoderEmbeddingsName, boxes=$decoderBoxesName, " +
                "origSize=$decoderOrigSizeName, reshapedSize=$decoderReshapedSizeName, " +
                "output=$decoderOutputName",
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
        private const val TAG = "SamService"

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
