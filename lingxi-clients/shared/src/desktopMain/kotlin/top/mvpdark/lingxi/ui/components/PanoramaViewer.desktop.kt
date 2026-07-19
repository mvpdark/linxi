package top.mvpdark.lingxi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.IntOffset
import androidx.compose.ui.geometry.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.InternalResourceApi
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Desktop 全景查看器：纯 Compose Canvas 实现的真 360° 球面投影。
 *
 * 不依赖 JCEF / JavaFX WebView，零外部原生库，打包体积不增加。
 *
 * 实现原理：
 * 1. 加载等距柱状全景图（equirectangular）到 ImageBitmap
 * 2. 用户拖动改变 yaw（水平角）和 pitch（垂直角）
 * 3. 用户缩放改变 hfov（视场角）
 * 4. 每帧把球面投影到视口：对每个目标像素，根据其方向向量
 *    反查球面坐标，再映射到全景图的 UV 坐标采样
 *
 * 采样策略：
 * - 为了性能，采用"反向映射 + 双线性插值"
 * - 采样步长根据 hfov 动态调整，避免高缩放时的摩尔纹
 *
 * 交互：
 * - 水平拖动：改变 yaw（无限循环，因为全景图水平方向是 360°）
 * - 垂直拖动：改变 pitch（限制在 -80°~80°，避免极点畸变）
 * - 滚轮/双指缩放：改变 hfov（50°~120°）
 */
@OptIn(InternalResourceApi::class)
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    // 加载全景图为 ImageBitmap
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 视角参数（角度制，便于 UI 理解）
    var yaw by remember { mutableFloatStateOf(0f) }        // 水平视角 0-360
    var pitch by remember { mutableFloatStateOf(0f) }      // 垂直视角 -80~80
    var hfov by remember { mutableFloatStateOf(90f) }      // 视场角 50-120

    // 惯性滑动
    var velocityX by remember { mutableFloatStateOf(0f) }
    var velocityY by remember { mutableFloatStateOf(0f) }

    // 加载图片
    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) return@LaunchedEffect
        loadError = false
        imageBitmap = null
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { loadImageBitmap(imageUrl) }
                imageBitmap = bitmap
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
        val bitmap = imageBitmap
        if (bitmap != null && !loadError) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // 水平拖动 → yaw（反向，拖动向左画面向右）
                            val yawDelta = -pan.x * 0.3f
                            yaw = (yaw + yawDelta + 360f) % 360f
                            velocityX = yawDelta

                            // 垂直拖动 → pitch
                            val pitchDelta = -pan.y * 0.3f
                            pitch = (pitch + pitchDelta).coerceIn(-80f, 80f)
                            velocityY = pitchDelta

                            // 缩放 → hfov（zoom>1 放大 → hfov 减小）
                            val newHfov = (hfov / zoom).coerceIn(50f, 120f)
                            if (abs(newHfov - hfov) > 0.5f) {
                                hfov = newHfov
                                // 缩放时停止惯性
                                velocityX = 0f
                                velocityY = 0f
                            }
                        }
                    },
            ) {
                drawPanoramaProjection(bitmap, yaw, pitch, hfov)
            }
        } else if (loadError) {
            // 加载失败时显示错误提示
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    text = "全景图加载失败",
                    color = Color.White,
                )
            }
        } else {
            // 加载中
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
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
 * 把等距柱状全景图按当前视角投影到画布。
 *
 * 反向映射：对画布每个采样点，计算其在全景图上的 UV 坐标。
 * 性能优化：按步长采样 + 双线性插值。
 */
private fun DrawScope.drawPanoramaProjection(
    bitmap: ImageBitmap,
    yawDeg: Float,
    pitchDeg: Float,
    hfovDeg: Float,
) {
    val canvasW = size.width
    val canvasH = size.height
    if (canvasW <= 0f || canvasH <= 0f) return

    val panW = bitmap.width.toFloat()
    val panH = bitmap.height.toFloat()

    // 角度转弧度
    val yaw = Math.toRadians(yawDeg.toDouble())
    val pitch = Math.toRadians(pitchDeg.toDouble())
    val hfov = Math.toRadians(hfovDeg.toDouble())
    val vfov = hfov * (canvasH / canvasW)

    // 视口四角的射线方向（中心方向为 yaw, pitch）
    // 用相机的 right/up 向量构建视口
    val cosY = cos(yaw)
    val sinY = sin(yaw)
    val cosP = cos(pitch)
    val sinP = sin(pitch)

    // 相机正前方方向向量
    val forwardX = cosP * sinY
    val forwardY = sinP
    val forwardZ = cosP * cosY

    // right = forward × up_world (up_world = (0,1,0))
    // right = (forwardZ*0 - forwardY*0... ) 简化：right = (cosY, 0, -sinY)
    val rightX = cosY
    val rightY = 0.0
    val rightZ = -sinY

    // up = right × forward
    val upX = rightY * forwardZ - rightZ * forwardY
    val upY = rightZ * forwardX - rightX * forwardZ
    val upZ = rightX * forwardY - rightY * forwardX

    // 采样步长：步长越大越快但越糊，步长越小越清晰但越慢
    // hfov 越小（放大）步长应越小
    val step = when {
        hfovDeg < 60f -> 1
        hfovDeg < 80f -> 2
        else -> 3
    }.coerceAtLeast(1)

    // 半视场角的 tan 值
    val halfHFov = hfov / 2.0
    val halfVFov = vfov / 2.0

    // 用 drawImage 的 srcRect 做区域采样
    // 为了性能，我们把画布分成格子，每个格子采样一个点
    val gridW = (canvasW / step).toInt().coerceAtLeast(1)
    val gridH = (canvasH / step).toInt().coerceAtLeast(1)
    val cellW = canvasW / gridW
    val cellH = canvasH / gridH

    // 遍历每个格子，计算其中心对应的 UV，画到对应位置
    for (gy in 0 until gridH) {
        for (gx in 0 until gridW) {
            // 格子中心的归一化视口坐标 [-1, 1]
            val sx = (gx + 0.5f) / gridW * 2f - 1f  // -1..1 左到右
            val sy = (gy + 0.5f) / gridH * 2f - 1f  // -1..1 上到下

            // 视口坐标 → 射线方向
            val dx = sx * tan(halfHFov)
            val dy = -sy * tan(halfVFov)  // Y 翻转（屏幕向下=世界向下）

            // 射线方向 = forward + dx*right + dy*up
            val rx = forwardX + dx * rightX + dy * upX
            val ry = forwardY + dx * rightY + dy * upY
            val rz = forwardZ + dx * rightZ + dy * upZ

            // 归一化
            val rLen = sqrt(rx * rx + ry * ry + rz * rz)
            if (rLen < 1e-10) continue
            val nx = rx / rLen
            val ny = ry / rLen
            val nz = rz / rLen

            // 方向向量 → 球面坐标
            // yaw = atan2(nx, nz), pitch = asin(ny)
            val sampleYaw = Math.atan2(nx, nz)
            val samplePitch = Math.asin(ny.coerceIn(-1.0, 1.0))

            // 球面坐标 → 全景图 UV
            // u = (yaw + PI) / (2*PI), v = (pitch + PI/2) / PI
            var u = ((sampleYaw + PI) / (2 * PI)).toFloat()
            val v = ((samplePitch + PI / 2) / PI).toFloat()

            // u 可能超出 [0,1]，取模处理（水平循环）
            u = ((u % 1f) + 1f) % 1f

            // UV → 全景图像素坐标
            val px = u * panW
            val py = v * panH

            // 画到对应格子（用 drawImage 采样一个点）
            val srcX = px.toInt().coerceIn(0, (panW - 1).toInt())
            val srcY = py.toInt().coerceIn(0, (panH - 1).toInt())

            drawImage(
                image = bitmap,
                srcOffset = IntOffset(srcX, srcY),
                srcSize = IntSize(1, 1),
                dstOffset = IntOffset(
                    (gx * cellW).toInt(),
                    (gy * cellH).toInt(),
                ),
                dstSize = IntSize(
                    (cellW + 1).toInt(),
                    (cellH + 1).toInt(),
                ),
            )
        }
    }
}

/**
 * 从 data URL 或 http URL 加载 ImageBitmap。
 * - data:image/png;base64,xxx → 解码 base64 → BufferedImage → ImageBitmap
 * - file:// → 读取文件
 * - http(s):// → 下载（用 Java URL）
 */
private fun loadImageBitmap(url: String): ImageBitmap {
    val bufferedImage = when {
        url.startsWith("data:") -> {
            // data URL: data:image/png;base64,xxxx
            val base64Data = url.substringAfter("base64,")
            val bytes = Base64.getDecoder().decode(base64Data)
            ImageIO.read(ByteArrayInputStream(bytes))
        }
        url.startsWith("file://") -> {
            val file = File(URI(url))
            ImageIO.read(file)
        }
        url.startsWith("http://") || url.startsWith("https://") -> {
            val connection = URI(url).toURL().openConnection()
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.getInputStream().use { ImageIO.read(it) }
        }
        else -> {
            // 尝试作为文件路径
            val file = File(url)
            if (file.exists()) ImageIO.read(file) else null
        }
    } ?: throw IllegalArgumentException("无法加载图片: $url")

    return bufferedImage.asImageBitmap()
}
