package top.mvpdark.lingxi.sam

import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.data.model.Bbox

/**
 * SAM 2 分割结果。
 *
 * @property success 是否分割成功
 * @property objects 每个物体的分割输出列表
 * @property error 失败时的错误信息
 */
data class SamSegmentResult(
    val success: Boolean,
    val objects: List<SamObject> = emptyList(),
    val error: String = "",
)

/**
 * 单个物体的分割输出。
 *
 * @property id 物体 ID（与输入对应）
 * @property polygon 归一化轮廓 [(x, y), ...]，0-1；无结果时为 null
 * @property maskPngB64 PNG 编码的 mask（base64，不含 data: 前缀）；无结果时为 null
 */
data class SamObject(
    val id: Int,
    val polygon: List<Pair<Float, Float>>? = null,
    val maskPngB64: String? = null,
)

/**
 * SAM 2 分割服务（跨平台抽象）。
 *
 * 对应前端 `sam-client.js` 的 `segmentImage` 能力，在端侧本地运行
 * EdgeTAM/SAM 2 模型，无需后端服务器资源。
 *
 * - Android: 基于 onnxruntime-android
 * - Desktop: 基于 onnxruntime-jvm
 *
 * 各平台在 `actual class` 中实现模型加载、推理与资源释放。
 * 预处理与 mask 后处理的纯算法部分复用 [SamImageProcessor] 与 [MaskPostProcessor]。
 *
 * @param context 平台上下文（Android 传递 Context，Desktop 为空占位）
 */
expect class SamService(context: PlatformContext) {

    /** 模型是否已加载就绪。 */
    var isReady: Boolean

    /**
     * 加载 SAM 模型。可多次调用，已加载时直接返回。
     *
     * @param onProgress 进度回调 (progress 0-100, file 当前下载文件名)
     */
    suspend fun loadModel(onProgress: (Int, String) -> Unit = { _, _ -> })

    /**
     * 对图片执行分割，提取每个物体的精确轮廓与 mask。
     *
     * @param imageBytes 原图字节流（PNG/JPEG）
     * @param objects 待分割物体列表，(id, bbox)，bbox 为归一化坐标 0-1
     * @return 分割结果
     */
    suspend fun segment(
        imageBytes: ByteArray,
        objects: List<Pair<Int, Bbox>>,
    ): SamSegmentResult

    /** 释放模型资源。 */
    fun close()
}
