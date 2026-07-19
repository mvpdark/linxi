package top.mvpdark.lingxi.ui.components

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.readResourceBytes
import java.io.File

/**
 * Android 全景查看器：用 WebView + Pannellum 实现 360° 球面投影。
 *
 * 核心策略（彻底修复白屏）：
 * 1. 把 pannellum.js、pannellum.css、panorama_viewer.html 写到应用缓存目录
 * 2. 把全景图（data URL 或 http URL）解码后写到同目录的 panorama.jpg
 * 3. 修改 HTML 中的图片路径为相对路径 "panorama.jpg"
 * 4. 用 loadUrl("file:///path/index.html") 加载，所有资源同源（file://）
 * 5. HTML 页面加载后自动调用 __loadPanorama("panorama.jpg")
 *
 * 之前的方案都失败的原因：
 * - 方案 A（临时文件 + file:// + loadDataWithBaseURL("about:blank")）：
 *   页面 origin 是 null，WebGL 加载 file:// 图片被跨域阻止
 * - 方案 B（data URL 嵌入 HTML + loadDataWithBaseURL）：
 *   data URL 太大（2-5MB），嵌入 HTML 后整体大小超限，WebView 加载失败
 * - 当前方案（全部 file:// + loadUrl）：
 *   页面和图片都是 file:// 协议，完全同源，WebGL 不会触发跨域检查
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(InternalResourceApi::class)
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 临时目录路径（所有资源放在同一目录，确保同源）
    var htmlPath by remember { mutableStateOf<String?>(null) }

    // 准备临时目录中的所有文件
    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) {
            htmlPath = null
            return@LaunchedEffect
        }
        htmlPath = withContext(Dispatchers.IO) {
            try {
                // 1. 创建临时目录
                val dir = File(context.cacheDir, "panorama_${System.currentTimeMillis()}")
                dir.mkdirs()

                // 2. 写入 pannellum.js
                val jsBytes = readResourceBytes("files/panorama/pannellum.js")
                File(dir, "pannellum.js").writeBytes(jsBytes)

                // 3. 写入 pannellum.css
                val cssBytes = readResourceBytes("files/panorama/pannellum.css")
                File(dir, "pannellum.css").writeBytes(cssBytes)

                // 4. 写入全景图片到 panorama.jpg
                val imageFile = File(dir, "panorama.jpg")
                when {
                    imageUrl.startsWith("data:") -> {
                        // data URL → 解码 base64 写入文件
                        val base64Data = imageUrl.substringAfter("base64,")
                        val imageBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                        imageFile.writeBytes(imageBytes)
                    }
                    imageUrl.startsWith("file://") -> {
                        // file:// → 复制文件
                        val srcPath = imageUrl.removePrefix("file://")
                        File(srcPath).copyTo(imageFile, overwrite = true)
                    }
                    imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                        // http URL → 下载到本地文件
                        val url = java.net.URL(imageUrl)
                        url.openStream().use { input ->
                            imageFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    else -> {
                        // 未知格式，尝试当作文件路径
                        try {
                            File(imageUrl).copyTo(imageFile, overwrite = true)
                        } catch (e: Exception) {
                            android.util.Log.e("PanoramaViewer", "Unknown image format: $imageUrl", e)
                            return@withContext null
                        }
                    }
                }

                // 5. 写入 index.html（修改图片路径为相对路径 panorama.jpg，页面加载后自动初始化）
                val htmlTemplate = readResourceBytes("files/panorama/panorama_viewer.html").decodeToString()
                val html = htmlTemplate
                    // 页面加载后自动调用 __loadPanorama("panorama.jpg")
                    // 添加 __panoLoaded 检查防止与 onPageFinished 中的调用重复
                    .replace(
                        "</body>",
                        """<script>
                        window.addEventListener('load', function() {
                            if (typeof pannellum !== 'undefined' && !window.__panoLoaded) {
                                window.__panoLoaded = true;
                                window.__loadPanorama('panorama.jpg');
                            }
                        });
                        </script>
                        </body>""",
                    )
                File(dir, "index.html").writeText(html)

                // 返回 HTML 文件的 file:// URL
                "file://${dir.absolutePath}/index.html"
            } catch (e: Exception) {
                android.util.Log.e("PanoramaViewer", "Failed to prepare panorama files", e)
                null
            }
        }
    }

    // WebView 只创建一次
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            // 关键：允许 WebView 加载 file:// URL 的资源
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.mediaPlaybackRequiresUserGesture = false
            // 启用硬件加速，WebGL 需要
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    android.util.Log.i("PanoramaViewer", "Page finished: $url")
                    // 页面加载完成后也调用一次 __loadPanorama（作为后备）
                    // HTML 中的 load 事件应该已经触发了，这里防止时序问题
                    view?.evaluateJavascript(
                        """if (typeof pannellum !== 'undefined' && !window.__panoLoaded) {
                            window.__panoLoaded = true;
                            window.__loadPanorama('panorama.jpg');
                        }""",
                        null,
                    )
                }
            }
        }
    }

    // HTML 路径就绪后用 loadUrl 加载（不用 loadDataWithBaseURL）
    LaunchedEffect(htmlPath) {
        htmlPath?.let { path ->
            webView.post {
                webView.loadUrl(path)
            }
        }
    }

    // 生命周期：Composable 离开时销毁 WebView + 清理临时文件
    DisposableEffect(Unit) {
        onDispose {
            webView.destroy()
            // 清理临时目录
            htmlPath?.let { path ->
                try {
                    val dir = File(path.removePrefix("file://").removeSuffix("/index.html"))
                    if (dir.exists()) {
                        dir.deleteRecursively()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PanoramaViewer", "Failed to cleanup temp files", e)
                }
            }
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
