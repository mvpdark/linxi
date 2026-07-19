package top.mvpdark.lingxi.sam

import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.data.model.Bbox

/**
 * SAM 2 分割服务 iOS (Kotlin/Native) 实现（P0 阶段 stub）。
 *
 * iOS 平台暂未集成 ONNX Runtime（onnxruntime 未提供官方 Kotlin/Native API），
 * P0 阶段所有方法均为 stub：
 * - [isReady] 始终为 false
 * - [loadModel] 报告失败，不抛异常（避免启动崩溃）
 * - [segment] 抛 [UnsupportedOperationException]（明确告知调用方不支持）
 *
 * 后续可考虑：
 * 1. 通过 Swift 桥接调用 onnxruntime C API（需 Swift framework）
 * 2. 使用 Core ML 转换模型
 * 3. 回退到服务端 SAM 分割 API
 *
 * @param context 平台上下文（iOS 为空占位，不使用）
 */
actual class SamService actual constructor(@Suppress("UNUSED_PARAMETER") context: PlatformContext) {

    @Volatile
    actual var isReady: Boolean = false
        private set

    actual suspend fun loadModel(onProgress: (Int, String) -> Unit) {
        // P0 stub：iOS 暂不支持 SAM 模型加载
        onProgress(0, "iOS 平台暂不支持 SAM 模型（P0 阶段 stub）")
        // 不设置 isReady = true，保持 false
    }

    actual suspend fun segment(
        imageBytes: ByteArray,
        objects: List<Pair<Int, Bbox>>,
    ): SamSegmentResult {
        // P0 stub：iOS 暂不支持 SAM 分割
        throw UnsupportedOperationException(
            "iOS 平台暂不支持 SAM 分割（P0 阶段 stub），" +
                "后续将通过 Swift 桥接 onnxruntime 或 Core ML 实现",
        )
    }

    actual fun close() {
        // no-op：无资源需要释放
    }
}
