package top.mvpdark.lingxi.ui.imageedit

import androidx.compose.runtime.Composable

/**
 * iOS 平台图片选择器（P0 阶段 stub）。
 *
 * P0 阶段返回 null，不打开任何选择器。
 * 后续用 PHPickerViewController（iOS 14+）实现原生图片选择：
 * ```kotlin
 * val config = PHPickerConfiguration().apply {
 *     filter = PHPickerFilter.imagesFilter
 *     selectionLimit = 1
 * }
 * val picker = PHPickerViewController(config)
 * picker.delegate = ...
 * // 需要 UIViewController-based 的桥接
 * ```
 *
 * @param onResult 选择图片后的回调（P0 阶段始终回调 null）
 * @return 启动选择器的函数（P0 阶段为空操作）
 */
@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    // P0 stub：后续用 PHPickerViewController 实现
    return {
        // 暂不支持，回调 null
        onResult(null)
    }
}
