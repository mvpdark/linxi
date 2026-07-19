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
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeShader
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.TileMode
import java.io.File
import java.net.URI
import java.util.Base64
import kotlin.math.abs

/**
 * Desktop 全景查看器：用 SkSL RuntimeShader 实现的真 360° 球面投影（GPU 加速）。
 *
 * 核心优势（对比纯 Compose Canvas 逐像素采样）：
 * - 单次 GPU draw call 完成整个画面的球面投影（vs 之前 100 万次 drawImage/帧）
 * - GPU 并行为每个输出像素计算反向球面映射 + 双线性纹理采样
 * - 零新增依赖：org.jetbrains.skia.* 已随 compose.desktop.currentOs 传递引入
 *
 * 实现原理：
 * 1. 加载全景图为 Skia Image（makeFromEncodedBytes，跳过 BufferedImage 中转）
 * 2. 构造 SkSL 着色器，把球面投影算法下放到 GPU
 * 3. 每帧只更新 uniform（yaw/pitch/hfov），GPU 重新并行计算所有像素
 *
 * SkSL 着色器逻辑（与 Pannellum WebGL fragment shader 等价）：
 * - 对每个输出像素，根据其视口坐标计算射线方向
 * - 射线方向 → 球面坐标(yaw, pitch) → 全景图 UV
 * - 用 panorama.eval(uv) 做双线性纹理采样
 * - 水平方向 TileMode.REPEAT（360° 无限循环）
 *
 * 交互：
 * - 水平拖动：yaw（0-360° 无限循环）
 * - 垂直拖动：pitch（-80°~80°，避免极点畸变）
 * - 滚轮/双指缩放：hfov（50°~120°）
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

    // 加载全景图（直接用 Skia 解码，跳过 BufferedImage）
    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) return@LaunchedEffect
        loadError = false
        skiaImage = null
        scope.launch {
            try {
                val img = withContext(Dispatchers.IO) { loadSkiaImage(imageUrl) }
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
 * SkSL 球面投影着色器源码。
 *
 * uniform：
 * - panorama: 全景图纹理 shader
 * - uYaw: 水平视角（弧度）
 * - uPitch: 垂直视角（弧度）
 * - uHfov: 水平视场角（弧度）
 * - uAspect: 画布宽高比（W/H）
 * - uPanW: 全景图宽度（像素）
 * - uPanH: 全景图高度（像素）
 * - uCanvasW: 画布宽度
 * - uCanvasH: 画布高度
 *
 * 输出：每个像素采样全景图对应位置的颜色
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
    // 视口归一化坐标 [-1, 1]
    float sx = (fragCoord.x / uCanvasW) * 2.0 - 1.0;
    float sy = (fragCoord.y / uCanvasH) * 2.0 - 1.0;

    // 视口坐标 → 射线方向
    float halfH = uHfov * 0.5;
    float halfV = halfH * uAspect;
    float dx = sx * tan(halfH);
    float dy = -sy * tan(halfV);

    // 相机基向量
    float cy = cos(uYaw);
    float sy_ = sin(uYaw);
    float cp = cos(uPitch);
    float sp = sin(uPitch);

    // forward = (cp*sin_, sp, cp*cy)
    float fX = cp * sy_;
    float fY = sp;
    float fZ = cp * cy;

    // right = (cy, 0, -sy_)
    float rX = cy;
    float rY = 0.0;
    float rZ = -sy_;

    // up = right × forward
    float uX = rY * fZ - rZ * fY;
    float uY = rZ * fX - rX * fZ;
    float uZ = rX * fY - rY * fX;

    // 射线方向
    float rx = fX + dx * rX + dy * uX;
    float ry = fY + dx * rY + dy * uY;
    float rz = fZ + dx * rZ + dy * uZ;

    // 归一化
    float rLen = sqrt(rx*rx + ry*ry + rz*rz);
    if (rLen < 1e-10) return half4(0,0,0,1);
    rx /= rLen;
    ry /= rLen;
    rz /= rLen;

    // 方向 → 球面坐标
    float sYaw = atan(rx, rz);
    float sPitch = asin(clamp(ry, -1.0, 1.0));

    // 球面坐标 → 全景图 UV
    float u = (sYaw + 3.14159265) / (2.0 * 3.14159265);
    float v = (sPitch + 1.5707963) / 3.14159265;

    // u 取模处理水平循环
    u = fract(u);

    // 采样全景图（GPU 双线性插值）
    vec2 samplePos = vec2(u * uPanW, v * uPanH);
    return panorama.eval(samplePos);
}
"""

/**
 * 用 SkSL RuntimeShader 绘制全景球面投影。
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
        TileMode.REPEAT,  // 水平 360° 循环
        TileMode.CLAMP,   // 垂直边界钳制
        FilterMode.LINEAR,  // GPU 双线性插值
        null,  // 无局部矩阵
    )

    // 创建 RuntimeShader
    val runtimeShader = RuntimeShader(SKSL_PANORAMA_SHADER)
    runtimeShader.setShader("panorama", panoramaShader)
    runtimeShader.setFloat("uYaw", Math.toRadians(yawDeg.toDouble()).toFloat())
    runtimeShader.setFloat("uPitch", Math.toRadians(pitchDeg.toDouble()).toFloat())
    runtimeShader.setFloat("uHfov", Math.toRadians(hfovDeg.toDouble()).toFloat())
    runtimeShader.setFloat("uAspect", canvasH / canvasW)
    runtimeShader.setFloat("uPanW", panW)
    runtimeShader.setFloat("uPanH", panH)
    runtimeShader.setFloat("uCanvasW", canvasW)
    runtimeShader.setFloat("uCanvasH", canvasH)

    // 用 Paint 应用 shader，绘制覆盖整个画布的矩形
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

    // 释放 native 资源
    paint.close()
    runtimeShader.close()
}

/**
 * 从 data URL / file:// / http(s):// 加载 Skia Image。
 * 使用 Skia 原生解码（makeFromEncodedBytes），跳过 BufferedImage 中转，
 * 解码后的 Image 可直接作为 GPU 纹理源。
 */
private fun loadSkiaImage(url: String): Image {
    val bytes = when {
        url.startsWith("data:") -> {
            val base64Data = url.substringAfter("base64,")
            Base64.getDecoder().decode(base64Data)
        }
        url.startsWith("file://") -> {
            File(URI(url)).readBytes()
        }
        url.startsWith("http://") || url.startsWith("https://") -> {
            val connection = URI(url).toURL().openConnection()
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.getInputStream().use { it.readBytes() }
        }
        else -> {
            val file = File(url)
            if (file.exists()) file.readBytes()
            else throw IllegalArgumentException("无法加载图片: $url")
        }
    }
    return Image.makeFromEncodedBytes(bytes)
}
