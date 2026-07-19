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
 * 1. 将 Pannellum + 全景图（base64）内联到一个自包含 HTML 文件
 * 2. 用系统默认浏览器打开该 HTML 文件（浏览器支持完整 WebGL）
 * 3. Compose UI 中显示静态预览 + "已在浏览器中打开" 提示
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
                val file = prepareSelfContainedHtml(imageUrl)
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
 * 准备自包含 HTML 文件（pannellum + base64 图片全部内联）。
 */
@OptIn(InternalResourceApi::class)
private suspend fun prepareSelfContainedHtml(imageUrl: String): File {
    // 1. 读取 pannellum.js 和 pannellum.css
    val jsCode = readResourceBytes("files/panorama/pannellum.js").decodeToString()
    val cssCode = readResourceBytes("files/panorama/pannellum.css").decodeToString()

    // 2. 读取全景图字节并转 base64
    val imageBytes = readImageBytes(imageUrl)
    val base64Str = Base64.getEncoder().encodeToString(imageBytes)

    // 3. 构建自包含 HTML
    val html = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>360° Panorama - 灵犀</title>
<style>
$cssCode
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { width: 100%; height: 100%; overflow: hidden; background: #000; }
#viewer { width: 100vw; height: 100vh; }
.hint {
  position: absolute; bottom: 16px; left: 50%; transform: translateX(-50%);
  color: rgba(255,255,255,0.8); font-size: 14px; font-family: -apple-system, sans-serif;
  background: rgba(0,0,0,0.4); padding: 6px 14px; border-radius: 16px;
  pointer-events: none; z-index: 10;
}
</style>
</head>
<body>
<div id="viewer"></div>
<div class="hint" id="hint">拖动画面查看 360° 视角</div>
<script>
// pannellum.js 内联
$jsCode
</script>
<script>
var viewer = null;
function base64ToBlobUrl(base64) {
  var pure = base64;
  if (pure.indexOf(',') !== -1) pure = pure.split(',')[1];
  var bytes = atob(pure);
  var arr = new Uint8Array(bytes.length);
  for (var i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i);
  return URL.createObjectURL(new Blob([arr], { type: 'image/jpeg' }));
}
(function() {
  var base64 = "data:image/jpeg;base64,$base64Str";
  var blobUrl = base64ToBlobUrl(base64);
  viewer = pannellum.viewer('viewer', {
    type: 'equirectangular',
    panorama: blobUrl,
    autoLoad: true,
    showZoomCtrl: true,
    showFullscreenCtrl: true,
    compass: false,
    minHfov: 50,
    maxHfov: 120,
    friction: 0.15
  });
  viewer.on('loadError', function() {
    document.getElementById('hint').textContent = '全景图加载失败';
  });
  viewer.on('error', function() {
    document.getElementById('hint').textContent = '全景图加载失败';
  });
  document.getElementById('viewer').addEventListener('mousedown', function() {
    var h = document.getElementById('hint');
    if (h) h.style.display = 'none';
  }, { once: true });
})();
</script>
</body>
</html>"""

    // 4. 写入临时文件
    val tempFile = File.createTempFile("panorama_", ".html")
    tempFile.writeText(html)
    tempFile.deleteOnExit()
    return tempFile
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
                url.openStream().use { it.readBytes() }
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
        throw UnsupportedOperationException("当前系统不支持自动打开浏览器，请手动打开: ${file.absolutePath}")
    }
}
