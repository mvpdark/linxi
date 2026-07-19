package top.mvpdark.lingxi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeShader
import org.jetbrains.skia.TileMode
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.abs

/**
 * iOS 全景查看器：用 SkSL RuntimeShader 实现的真 360° 球面投影（GPU 加速）。
 *
 * 与 Desktop 端共享同一套 SkSL 着色器方案（org.jetbrains.skia.* 通过 Skiko 在
 * iOS 上自动可用），仅差异在图片加载层：
 * - Desktop：java.io.File / java.net.URI / java.util.Base64（JVM）
 * - iOS：platform.Foundation.NSData / NSURL + kotlin.io.encoding.Base64（Kotlin/Native）
 *
 * 核心优势（同 Desktop）：
 * - 单次 GPU draw call 完成整个画面的球面投影
 * - GPU 并行为每个输出像素计算反向球面映射 + 双线性纹理采样
 * - 零新增依赖：org.jetbrains.skia.* 随 Compose Multiplatform Skiko 传递引入
 *
 * 交互（同 Desktop）：
 * - 水平拖动：yaw（0-360° 无限循环）
 * - 垂直拖动：pitch（-80°~80°，避免极点畸变）
 * - 双指缩放：hfov（50°~120°）
 * - 惯性滑动：0.92 衰减
 */
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    var skiaImage by remember { mutableStateOf<Image?>(null) }
    var imageWidth by remember { mutableFloatStateOf(0f) }
    var imageHeight by remember { mutableFloatStateOf(0f) }
    var loadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 视角参数
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var hfov by remember { mutableFloatStateOf(90f) }

    // 惯性滑动
    var velocityX by remember { mutableFloatStateOf(0f) }
    var velocityY by remember { mutableFloatStateOf(0f) }

    // 加载全景图（直接用 Skia 解码）
    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) return@LaunchedEffect
        loadError = false
        skiaImage = null
        scope.launch {
            try {
                // 使用 Dispatchers.Default 在后台线程加载（iOS 无独立 IO dispatcher）
                val img = withContext(Dispatchers.Default) { loadSkiaImage(imageUrl) }
                skiaImage = img
                imageWidth = img.width.toFloat()
                imageHeight = img.height.toFloat()
            } catch (e: Exception) {
                println("PanoramaViewer: Failed to load image: ${e.message}")
                loadError = true
            }
        }
    }

    // 惯性滑动动画
    LaunchedEffect(velocityX, velocityY) {
        if (abs(velocityX) < 0.5f && abs(velocityY) < 0.5f) return@LaunchedEffect
        while (abs(velocityX) >= 0.5f || abs(velocityY) >= 0.5f) {
            yaw = (yaw + velocityX + 360f) % 360f
            pitch = (pitch + velocityY).coerceIn(-80f, 80f)
            velocityX *= 0.92f
            velocityY *= 0.92f
            delay(16)
        }
        velocityX = 0f
        velocityY = 0f
    }

    Box(
        modifier = modifier.background(Color.Black),
    ) {
        val img = skiaImage
        if (img != null && !loadError && imageWidth > 0f && imageHeight > 0f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val yawDelta = -pan.x * 0.3f
                            yaw = (yaw + yawDelta + 360f) % 360f
                            velocityX = yawDelta

                            val pitchDelta = -pan.y * 0.3f
                            pitch = (pitch + pitchDelta).coerceIn(-80f, 80f)
                            velocityY = pitchDelta

                            val newHfov = (hfov / zoom).coerceIn(50f, 120f)
                            if (abs(newHfov - hfov) > 0.5f) {
                                hfov = newHfov
                                velocityX = 0f
                                velocityY = 0f
                            }
                        }
                    },
            ) {
                drawPanoramaWithShader(img, yaw, pitch, hfov, imageWidth, imageHeight)
            }
        } else if (loadError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    text = "全景图加载失败",
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
            }
        }
    }
}

/**
 * SkSL 球面投影着色器源码（与 Desktop 完全一致）。
 *
 * uniform：
 * - panorama: 全景图纹理 shader
 * - uYaw: 水平视角（弧度）
 * - uPitch: 垂直视角（弧度）
 * - uHfov: 水平视场角（弧度）
 * - uAspect: 画布宽高比（H/W）
 * - uPanW/uPanH: 全景图宽高（像素）
 * - uCanvasW/uCanvasH: 画布宽高
 */
private const val SKSL_PANORAMA_SHADER = """
uniform shader panorama;
uniform float uYaw;
uniform float uPitch;
uniform float uHfov;
uniform float uAspect;
uniform float uPanW;
uniform float uPanH;
uniform float uCanvasW;
uniform float uCanvasH;

half4 main(vec2 fragCoord) {
    float sx = (fragCoord.x / uCanvasW) * 2.0 - 1.0;
    float sy = (fragCoord.y / uCanvasH) * 2.0 - 1.0;

    float halfH = uHfov * 0.5;
    float halfV = halfH * uAspect;
    float dx = sx * tan(halfH);
    float dy = -sy * tan(halfV);

    float cy = cos(uYaw);
    float sy_ = sin(uYaw);
    float cp = cos(uPitch);
    float sp = sin(uPitch);

    float fX = cp * sy_;
    float fY = sp;
    float fZ = cp * cy;

    float rX = cy;
    float rY = 0.0;
    float rZ = -sy_;

    float uX = rY * fZ - rZ * fY;
    float uY = rZ * fX - rX * fZ;
    float uZ = rX * fY - rY * fX;

    float rx = fX + dx * rX + dy * uX;
    float ry = fY + dx * rY + dy * uY;
    float rz = fZ + dx * rZ + dy * uZ;

    float rLen = sqrt(rx*rx + ry*ry + rz*rz);
    if (rLen < 1e-10) return half4(0,0,0,1);
    rx /= rLen;
    ry /= rLen;
    rz /= rLen;

    float sYaw = atan(rx, rz);
    float sPitch = asin(clamp(ry, -1.0, 1.0));

    float u = (sYaw + 3.14159265) / (2.0 * 3.14159265);
    float v = (sPitch + 1.5707963) / 3.14159265;

    u = fract(u);

    vec2 samplePos = vec2(u * uPanW, v * uPanH);
    return panorama.eval(samplePos);
}
"""

/**
 * 用 SkSL RuntimeShader 绘制全景球面投影（与 Desktop 逻辑一致）。
 * 单次 draw call，GPU 并行处理所有像素。
 */
private fun DrawScope.drawPanoramaWithShader(
    image: Image,
    yawDeg: Float,
    pitchDeg: Float,
    hfovDeg: Float,
    panW: Float,
    panH: Float,
) {
    val canvasW = size.width
    val canvasH = size.height
    if (canvasW <= 0f || canvasH <= 0f) return

    // 创建全景图 shader（水平 REPEAT 循环，垂直 CLAMP）
    val panoramaShader = image.makeShader(
        TileMode.REPEAT,
        TileMode.CLAMP,
        FilterMode.LINEAR,
        null,
    )

    // 创建 RuntimeShader
    val runtimeShader = RuntimeShader(SKSL_PANORAMA_SHADER)
    runtimeShader.setShader("panorama", panoramaShader)
    runtimeShader.setFloat("uYaw", toRadians(yawDeg))
    runtimeShader.setFloat("uPitch", toRadians(pitchDeg))
    runtimeShader.setFloat("uHfov", toRadians(hfovDeg))
    runtimeShader.setFloat("uAspect", canvasH / canvasW)
    runtimeShader.setFloat("uPanW", panW)
    runtimeShader.setFloat("uPanH", panH)
    runtimeShader.setFloat("uCanvasW", canvasW)
    runtimeShader.setFloat("uCanvasH", canvasH)

    val paint = Paint().apply {
        shader = runtimeShader
    }

    drawIntoCanvas { composeCanvas ->
        val nativeCanvas = composeCanvas.nativeCanvas
        nativeCanvas.drawRect(
            Rect.makeXYWH(0f, 0f, canvasW, canvasH),
            paint,
        )
    }

    paint.close()
    runtimeShader.close()
}

/**
 * 角度转弧度（Kotlin/Native 无 Math.toRadians，用纯算法替代）。
 */
private fun toRadians(deg: Float): Float = (deg * PI / 180.0).toFloat()

/**
 * 从 data URL / file:// / http(s):// 加载 Skia Image（iOS 版）。
 *
 * 与 Desktop 区别：
 * - data: URL → kotlin.io.encoding.Base64（替代 java.util.Base64）
 * - file:// / http(s):// → NSData.dataWithContentsOfURL（替代 java.net.URI + File）
 * - 本地路径 → NSData.dataWithContentsOfFile
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
private fun loadSkiaImage(url: String): Image {
    val bytes = when {
        url.startsWith("data:") -> {
            val base64Data = url.substringAfter("base64,")
            Base64.decode(base64Data)
        }
        url.startsWith("file://") -> {
            val nsUrl = NSURL(string = url)
            NSData.dataWithContentsOfURL(nsUrl)?.toByteArray()
                ?: throw IllegalArgumentException("无法加载文件图片: $url")
        }
        url.startsWith("http://") || url.startsWith("https://") -> {
            val nsUrl = NSURL(string = url)
            // 同步加载（在 Dispatchers.Default 后台线程执行）
            NSData.dataWithContentsOfURL(nsUrl)?.toByteArray()
                ?: throw IllegalArgumentException("无法加载网络图片: $url")
        }
        else -> {
            // 尝试作为本地文件路径加载
            NSData.dataWithContentsOfFile(url)?.toByteArray()
                ?: throw IllegalArgumentException("无法加载图片: $url")
        }
    }
    return Image.makeFromEncodedBytes(bytes)
}

/**
 * NSData → ByteArray 转换辅助函数。
 *
 * 使用 memcpy 从 NSData 的 native 内存拷贝到 Kotlin ByteArray。
 * （Kotlin/Native 无 ByteArray.toNSData() 扩展，需手动桥接）
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val sourcePtr = this.bytes ?: return ByteArray(0)
    val arr = ByteArray(length)
    arr.usePinned { pinned ->
        memcpy(pinned.addressOf(0), sourcePtr, length.toULong())
    }
    return arr
}
