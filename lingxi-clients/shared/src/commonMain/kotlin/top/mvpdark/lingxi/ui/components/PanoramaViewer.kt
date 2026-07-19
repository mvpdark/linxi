package top.mvpdark.lingxi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlin.math.roundToInt

/**
 * 全景图查看器：支持水平 360° 循环拖拽 + 双指缩放。
 *
 * 将 2:1 equirectangular 全景图作为长条纹理水平循环平移，
 * 拖到右边缘无缝回到左边缘，实现"伪 360°"浏览体验。
 * 双指捏合调整视野宽度（zoom），单指拖拽改变水平视角。
 *
 * 实现：纯 Compose Canvas + 手势检测，零额外依赖，Android/Desktop 全平台共享。
 *
 * @param imageUrl 全景图 URL（data URL 或 http URL）
 * @param modifier 修饰符
 */
@Composable
fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
) {
    // 水平偏移量（像素），随拖拽变化
    var offsetX by remember { mutableFloatStateOf(0f) }
    // 缩放级别（1.0 = 适配宽度，2.0 = 放大 2 倍）
    var zoom by remember { mutableFloatStateOf(1f) }

    val painter = rememberAsyncImagePainter(model = imageUrl)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    // 水平拖拽：更新偏移量
                    offsetX += pan.x
                    // 双指缩放：限制在 1.0 ~ 4.0
                    zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val painterState = painter.state
        // 使用 Canvas 绘制循环平移的全景图
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (painterState is AsyncImagePainter.State.Success) {
                drawPanoramaLoop(
                    drawScope = this,
                    painter = painter,
                    offsetX = offsetX,
                    zoom = zoom,
                )
            }
        }

        // 加载中状态
        if (painterState is AsyncImagePainter.State.Loading) {
            // Canvas 会自动留空，这里不额外绘制 loading
        }
    }
}

/**
 * 在 DrawScope 中水平循环绘制全景图，实现 360° 无缝平移。
 *
 * 算法：
 * 1. 计算缩放后的图片绘制宽度 drawWidth
 * 2. 将 offsetX 对 drawWidth 取模，得到 [0, drawWidth) 范围的有效偏移
 * 3. 从 -effectiveOffset 开始，每 drawWidth 间距绘制一张图，直到覆盖画布宽度
 */
private fun drawPanoramaLoop(
    drawScope: DrawScope,
    painter: androidx.compose.ui.graphics.painter.Painter,
    offsetX: Float,
    zoom: Float,
) {
    with(drawScope) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 原始图片尺寸
        val intrinsicWidth = painter.intrinsicSize.width
        val intrinsicHeight = painter.intrinsicSize.height

        if (intrinsicWidth <= 0f || intrinsicHeight <= 0f) return

        // 按 zoom 缩放：以画布宽度为基准
        // zoom=1 时图片宽度=画布宽度，zoom=2 时图片宽度=2倍画布宽度
        val drawWidth = canvasWidth * zoom
        val drawHeight = drawWidth * (intrinsicHeight / intrinsicWidth)

        // 垂直居中
        val yOffset = (canvasHeight - drawHeight) / 2f

        // 水平循环：将 offsetX 归一化到 [0, drawWidth) 范围
        val effectiveOffset = ((offsetX % drawWidth) + drawWidth) % drawWidth

        // 从左侧外边开始绘制，循环填充直到覆盖整个画布宽度
        var x = -effectiveOffset
        while (x < canvasWidth) {
            translate(left = x, top = yOffset) {
                with(painter) {
                    draw(
                        size = Size(drawWidth, drawHeight),
                    )
                }
            }
            x += drawWidth
        }
    }
}
