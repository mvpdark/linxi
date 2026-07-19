package top.mvpdark.lingxi.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.readResourceBytes
import top.mvpdark.lingxi.core.util.PlatformLogger
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Base64

/**
 * Desktop 全景查看器。
 *
 * 由于 Compose Desktop 环境无法直接运行 WebGL（JCEF/JavaFX 依赖过重，
 * 且 jlink 打包复杂），采用「浏览器打开」方案：
 *
 * 1. 将 pannellum.js、pannellum.css、panorama_data.js（base64 图片）写入临时目录
 * 2. 生成 index.html 引用上述文件
 * 3. 用系统默认浏览器打开 index.html（浏览器支持完整 WebGL）
 * 4. Compose UI 中显示静态预览 + "已在浏览器中打开" 提示
 *
 * 注意：pannellum.js 包含 $a 等 JS 变量名，不能内联到 Kotlin """...""" 模板字符串
 * （Kotlin 会将 $a 解析为模板表达式导致编译错误），必须作为独立文件通过
 * <script src="pannellum.js"> 引用。
 *
 * @param imageUrl 全景图 URL（data URL 或 http URL）
 * @param modifier 修饰符
 */
@OptIn(InternalResourceApi::class)
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    var browserOpened by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var htmlFile by remember { mutableStateOf<File?>(null) }

    // 准备 HTML 文件并在浏览器中打开
    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) return@LaunchedEffect
        error = null
        browserOpened = false
        withContext(Dispatchers.IO) {
            try {
                val file = preparePanoramaFiles(imageUrl)
                htmlFile = file
                openInBrowser(file)
                browserOpened = true
            } catch (e: Exception) {
                PlatformLogger.e("PanoramaViewer", "Failed to open panorama in browser", e)
                error = "打开全景图失败: ${e.message}"
            }
        }
    }

    val painter = rememberAsyncImagePainter(imageUrl)
    val state by painter.state.collectAsState()

    Box(
        modifier = modifier.background(Color.Black),
    ) {
        // 静态预览图
        when (val s = state) {
            is AsyncImagePainter.State.Success -> {
                Image(
                    painter = painter,
                    contentDescription = "全景图预览",
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is AsyncImagePainter.State.Loading,
            AsyncImagePainter.State.Empty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                }
            }
            is AsyncImagePainter.State.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("预览加载失败", color = Color.White)
                }
            }
        }

        // 底部状态提示
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                error != null -> {
                    Text(
                        text = error!!,
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                browserOpened -> {
                    Text(
                        text = "360° 全景图已在浏览器中打开",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            htmlFile?.let { file ->
                                runCatching { openInBrowser(file) }
                                    .onFailure { error = "重新打开失败: ${it.message}" }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("重新打开")
                    }
                }
                else -> {
                    Text(
                        text = "正在准备 360° 全景图...",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * 准备全景图文件目录（pannellum.js + pannellum.css + panorama_data.js + index.html）。
 *
 * 关键：pannellum.js 作为独立文件写入，不内联到 Kotlin 模板字符串，
 * 避免 $a 等 JS 变量名被 Kotlin 解析为模板表达式。
 */
@OptIn(InternalResourceApi::class)
private suspend fun preparePanoramaFiles(imageUrl: String): File {
    // 1. 创建临时目录
    val dir = File(System.getProperty("java.io.tmpdir"), "panorama_${System.currentTimeMillis()}")
    dir.mkdirs()

    // 注册 JVM 关闭钩子：在 JVM 退出时递归删除整个临时目录，避免临时文件泄漏。
    // 说明：File.deleteOnExit() 只能删除单个文件，无法删除非空目录（JVM 退出时
    // 目录若仍非空则删除失败），因此采用 shutdown hook + deleteRecursively() 方案，
    // 确保 pannellum.js / pannellum.css / panorama_data.js / index.html 及目录本身
    // 都能被彻底清理。
    Runtime.getRuntime().addShutdownHook(Thread {
        dir.deleteRecursively()
    })

    // 2. 写入 pannellum.js（独立文件，不内联）
    val jsBytes = readResourceBytes("files/panorama/pannellum.js")
    File(dir, "pannellum.js").writeBytes(jsBytes)

    // 3. 写入 pannellum.css
    val cssBytes = readResourceBytes("files/panorama/pannellum.css")
    File(dir, "pannellum.css").writeBytes(cssBytes)

    // 4. 读取全景图字节并转 base64，写入 panorama_data.js
    val imageBytes = readImageBytes(imageUrl)
    val base64Str = Base64.getEncoder().encodeToString(imageBytes)
    val dataJs = "window.__panoramaBase64 = \"data:image/jpeg;base64," + base64Str + "\";"
    File(dir, "panorama_data.js").writeText(dataJs)

    // 5. 生成 index.html（通过 <script src> 引用外部文件，不内联 JS）
    val html = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"zh-CN\">")
        appendLine("<head>")
        appendLine("<meta charset=\"UTF-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        appendLine("<title>360 Panorama - Lingxi</title>")
        appendLine("<link rel=\"stylesheet\" href=\"pannellum.css\">")
        appendLine("<style>")
        appendLine("* { margin: 0; padding: 0; box-sizing: border-box; }")
        appendLine("html, body { width: 100%; height: 100%; overflow: hidden; background: #000; }")
        appendLine("#viewer { width: 100vw; height: 100vh; }")
        appendLine(".hint {")
        appendLine("  position: absolute; bottom: 16px; left: 50%; transform: translateX(-50%);")
        appendLine("  color: rgba(255,255,255,0.8); font-size: 14px; font-family: -apple-system, sans-serif;")
        appendLine("  background: rgba(0,0,0,0.4); padding: 6px 14px; border-radius: 16px;")
        appendLine("  pointer-events: none; z-index: 10;")
        appendLine("}")
        appendLine("</style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<div id=\"viewer\"></div>")
        appendLine("<div class=\"hint\" id=\"hint\">拖动画面查看 360 视角</div>")
        appendLine("<script src=\"panorama_data.js\"></script>")
        appendLine("<script src=\"pannellum.js\"></script>")
        appendLine("<script>")
        appendLine("var viewer = null;")
        appendLine("function base64ToBlobUrl(base64) {")
        appendLine("  var pure = base64;")
        appendLine("  if (pure.indexOf(',') !== -1) pure = pure.split(',')[1];")
        appendLine("  var bytes = atob(pure);")
        appendLine("  var arr = new Uint8Array(bytes.length);")
        appendLine("  for (var i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i);")
        appendLine("  return URL.createObjectURL(new Blob([arr], { type: 'image/jpeg' }));")
        appendLine("}")
        appendLine("(function() {")
        appendLine("  var blobUrl = base64ToBlobUrl(window.__panoramaBase64);")
        appendLine("  viewer = pannellum.viewer('viewer', {")
        appendLine("    type: 'equirectangular',")
        appendLine("    panorama: blobUrl,")
        appendLine("    autoLoad: true,")
        appendLine("    showZoomCtrl: true,")
        appendLine("    showFullscreenCtrl: true,")
        appendLine("    compass: false,")
        appendLine("    minHfov: 50,")
        appendLine("    maxHfov: 120,")
        appendLine("    friction: 0.15")
        appendLine("  });")
        appendLine("  viewer.on('loadError', function() {")
        appendLine("    document.getElementById('hint').textContent = '全景图加载失败';")
        appendLine("  });")
        appendLine("  viewer.on('error', function() {")
        appendLine("    document.getElementById('hint').textContent = '全景图加载失败';")
        appendLine("  });")
        appendLine("  document.getElementById('viewer').addEventListener('mousedown', function() {")
        appendLine("    var h = document.getElementById('hint');")
        appendLine("    if (h) h.style.display = 'none';")
        appendLine("  }, { once: true });")
        appendLine("})();")
        appendLine("</script>")
        appendLine("</body>")
        appendLine("</html>")
    }

    val htmlFile = File(dir, "index.html")
    htmlFile.writeText(html)
    // 临时文件清理由上方注册的 JVM 关闭钩子统一处理（递归删除整个临时目录），
    // 无需再对单个文件调用 deleteOnExit()。
    return htmlFile
}

/**
 * 读取图片字节。
 */
private suspend fun readImageBytes(imageUrl: String): ByteArray {
    return withContext(Dispatchers.IO) {
        when {
            imageUrl.startsWith("data:") -> {
                val base64Data = imageUrl.substringAfter("base64,")
                Base64.getDecoder().decode(base64Data)
            }
            imageUrl.startsWith("file://") -> {
                val path = imageUrl.removePrefix("file://")
                File(path).readBytes()
            }
            imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                val url = URI(imageUrl).toURL()
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.useCaches = false
                conn.inputStream.use { it.readBytes() }
            }
            else -> {
                try {
                    File(imageUrl).readBytes()
                } catch (e: Exception) {
                    throw IllegalArgumentException("无法识别的图片URL格式: $imageUrl", e)
                }
            }
        }
    }
}

/**
 * 在系统默认浏览器中打开文件。
 */
private fun openInBrowser(file: File) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(file.toURI())
    } else {
        throw UnsupportedOperationException("当前系统不支持自动打开浏览器，请手动打开: " + file.absolutePath)
    }
}
