package top.mvpdark.lingxi.ui.emoji

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.readResourceBytes

/**
 * APNG 动画表情组件。
 *
 * 从 Compose Resources 加载 APNG 文件，解析帧数据，在 Compose 中播放动画。
 *
 * 特性：
 * - 纯 Kotlin APNG 解析（无第三方依赖）
 * - 平台原生 PNG 解码（Android: BitmapFactory, Desktop: ImageIO）
 * - 按帧延迟自动播放，支持循环
 * - 加载中显示空白占位
 *
 * @param resourcePath Compose Resources 中的文件路径（如 "files/emoji/animated/idle.apng"）。
 * @param modifier 修饰符。
 * @param size 表情尺寸（正方形）。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun AnimatedEmoji(
    resourcePath: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    // 加载并解析 APNG
    val apngState = produceState<ApngParser.ApngData?>(initialValue = null, resourcePath) {
        value = withContext(Dispatchers.Default) {
            try {
                val bytes = readResourceBytes(resourcePath)
                ApngParser().parse(bytes)
            } catch (e: Exception) {
                null
            }
        }
    }

    val apngData = apngState.value

    if (apngData == null || apngData.frames.isEmpty()) {
        // 加载中或解析失败：显示空白占位
        Box(modifier = modifier.size(size))
        return
    }

    // 解码所有帧到 ImageBitmap（在 Default 线程）
    val framesState = produceState<List<ImageBitmap>?>(initialValue = null, apngData) {
        value = withContext(Dispatchers.Default) {
            apngData.frames.mapNotNull { frame ->
                decodePngBytes(frame.pngBytes)
            }
        }
    }

    val frames = framesState.value
    if (frames.isNullOrEmpty()) {
        Box(modifier = modifier.size(size))
        return
    }

    // 单帧：直接显示
    if (frames.size == 1) {
        Image(
            bitmap = frames[0],
            contentDescription = null,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
        )
        return
    }

    // 多帧：动画播放
    var currentFrame by remember(resourcePath) { mutableIntStateOf(0) }

    LaunchedEffect(resourcePath, frames.size) {
        while (true) {
            val frameDelay = apngData.frames.getOrNull(currentFrame)?.delayMs ?: 100
            // 最小延迟 50ms，防止过快闪烁
            val effectiveDelay = frameDelay.coerceAtLeast(50)
            delay(effectiveDelay)
            currentFrame = (currentFrame + 1) % frames.size
        }
    }

    Image(
        bitmap = frames[currentFrame],
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

/**
 * APNG 表情占位符（静态，不加载资源）。
 *
 * 用于加载前的占位显示。
 */
@Composable
fun EmojiPlaceholder(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    Box(modifier = modifier.size(size))
}
