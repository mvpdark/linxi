package top.mvpdark.lingxi.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter

/**
 * Desktop 全景查看器（简化版）。
 *
 * 背景：Compose Multiplatform Desktop 环境下 `org.jetbrains.skia` 的
 * `RuntimeShader` / `TileMode` / `FilterMode` / `nativeCanvas` /
 * `makeFromEncodedBytes` 等 API 不可用（CI 报 Unresolved reference），
 * 因此改用纯 Compose Canvas + Coil3 标准 API 实现：
 *
 * - Coil3 [rememberAsyncImagePainter] 加载图片（支持 data URL / http(s) URL / file 路径）
 * - 水平拖动浏览 360° 全景（[detectTransformGestures] 的 pan，ContentScale.FillHeight）
 * - 鼠标滚轮缩放（PointerEventType.Scroll），范围 1x ~ 4x
 * - 触控板双指缩放（detectTransformGestures 的 zoom）
 * - 加载中显示 CircularProgressIndicator，失败显示错误文字
 * - 黑色背景（Noir Aurum 风格）
 *
 * 不依赖任何 `org.jetbrains.skia.*` API，仅使用 Compose Multiplatform + Coil3 标准 API。
 *
 * @param imageUrl 全景图 URL（data URL 或 http URL）
 * @param modifier 修饰符
 */
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    // 缩放与水平偏移状态
    var zoom by remember { mutableFloatStateOf(MIN_ZOOM) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val painter = rememberAsyncImagePainter(imageUrl)
    val state by painter.state.collectAsState()

    Box(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds(),
    ) {
        when (val s = state) {
            is AsyncImagePainter.State.Empty,
            is AsyncImagePainter.State.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
            }

            is AsyncImagePainter.State.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "全景图加载失败",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            is AsyncImagePainter.State.Success -> {
                val image = s.result.image
                val imgW = image.width
                val imgH = image.height

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val containerW = constraints.maxWidth.toFloat()
                    val containerH = constraints.maxHeight.toFloat()
                    // ContentScale.FillHeight：图片高度撑满容器高度，宽度按原始比例缩放
                    // width/height 可能为 -1（无固有尺寸），此时回退到容器宽度（无水平滚动）
                    val drawnW = if (imgW > 0 && imgH > 0) {
                        containerH * imgW.toFloat() / imgH.toFloat()
                    } else {
                        containerW
                    }
                    // 当前缩放下，图片居中后允许的最大水平平移距离（避免拖出可视区域）
                    val maxPan = ((drawnW * zoom - containerW) / 2f).coerceAtLeast(0f)
                    val clampedOffset = offsetX.coerceIn(-maxPan, maxPan)

                    Image(
                        painter = painter,
                        contentDescription = "全景图",
                        alignment = Alignment.Center,
                        contentScale = ContentScale.FillHeight,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                translationX = clampedOffset,
                                scaleX = zoom,
                                scaleY = zoom,
                            )
                            // 水平拖动 / 触控板双指缩放
                            .pointerInput(drawnW, containerW) {
                                detectTransformGestures { _, pan, zoomChange, _ ->
                                    val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    val newMaxPan =
                                        ((drawnW * newZoom - containerW) / 2f).coerceAtLeast(0f)
                                    zoom = newZoom
                                    offsetX = (offsetX + pan.x).coerceIn(-newMaxPan, newMaxPan)
                                }
                            }
                            // 鼠标滚轮缩放（消费事件，避免外层 LazyColumn 同步滚动）
                            .pointerInput(drawnW, containerW) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type != PointerEventType.Scroll) continue
                                        val change = event.changes.firstOrNull() ?: continue
                                        val delta = change.scrollDelta.y
                                        if (delta == 0f) continue
                                        val newZoom = (zoom - delta * SCROLL_ZOOM_FACTOR)
                                            .coerceIn(MIN_ZOOM, MAX_ZOOM)
                                        val newMaxPan =
                                            ((drawnW * newZoom - containerW) / 2f)
                                                .coerceAtLeast(0f)
                                        zoom = newZoom
                                        offsetX = offsetX.coerceIn(-newMaxPan, newMaxPan)
                                        change.consume()
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
private const val SCROLL_ZOOM_FACTOR = 0.1f
