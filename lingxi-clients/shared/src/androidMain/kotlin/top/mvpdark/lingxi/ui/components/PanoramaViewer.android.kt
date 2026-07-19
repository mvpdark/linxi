package top.mvpdark.lingxi.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lingxi_clients.shared.generated.resources.Res
import java.io.File

/**
 * Android 全景查看器：用 WebView + Pannellum 实现 360° 球面投影。
 *
 * 核心策略（彻底修复白屏）：
 * 1. 把 pannellum.js、pannellum.css、panorama_viewer.html 写到应用缓存目录
 * 2. 把全景图转成 base64 写入 panorama_data.js（window.__panoramaBase64 = "..."）
 * 3. HTML 加载 panorama_data.js 后，JS 将 base64 转为 blob: URL（同源，XHR 可靠）
 * 4. 用 pannellum.viewer 加载 blob: URL，避免 file:// XHR responseType="blob" 返回 null 的问题
 *
 * 之前方案失败的原因：
 * - file:// + XHR responseType="blob" 在 Android WebView API 29+ 中返回 null
 * - 导致 Pannellum 的 FileReader.readAsBinaryString(null) 静默崩溃 → 白屏
 * - blob: URL 是同源的，XHR 可以可靠加载，彻底解决问题
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var htmlPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) {
            htmlPath = null
            loadError = null
            return@LaunchedEffect
        }
        loadError = null
        htmlPath = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "panorama_${System.currentTimeMillis()}")
                dir.mkdirs()

                // 1. 写入 pannellum.js
                val jsBytes = Res.readBytes("files/panorama/pannellum.js")
                File(dir, "pannellum.js").writeBytes(jsBytes)

                // 2. 写入 pannellum.css
                val cssBytes = Res.readBytes("files/panorama/pannellum.css")
                File(dir, "pannellum.css").writeBytes(cssBytes)

                // 3. 读取全景图字节并转 base64
                val imageBytes = when {
                    imageUrl.startsWith("data:") -> {
                        val base64Data = imageUrl.substringAfter("base64,")
                        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    }
                    imageUrl.startsWith("file://") -> {
                        val srcPath = imageUrl.removePrefix("file://")
                        File(srcPath).readBytes()
                    }
                    imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                        val url = java.net.URL(imageUrl)
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
                            Log.e("PanoramaViewer", "Unknown image format: $imageUrl", e)
                            return@withContext null
                        }
                    }
                }

                // 4. 写入 panorama_data.js（base64 数据）
                val base64Str = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                val dataJs = "window.__panoramaBase64 = \"data:image/jpeg;base64,$base64Str\";"
                File(dir, "panorama_data.js").writeText(dataJs)

                // 5. 写入 index.html（直接使用模板，HTML 内置自动加载逻辑）
                val htmlTemplate = Res.readBytes("files/panorama/panorama_viewer.html").decodeToString()
                File(dir, "index.html").writeText(htmlTemplate)

                "file://${dir.absolutePath}/index.html"
            } catch (e: Exception) {
                Log.e("PanoramaViewer", "Failed to prepare panorama files", e)
                loadError = "全景图加载失败: ${e.message}"
                null
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.mediaPlaybackRequiresUserGesture = false
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

            // 启用 WebView 调试（chrome://inspect）
            WebView.setWebContentsDebuggingEnabled(true)

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    Log.d("PanoramaJS", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i("PanoramaViewer", "Page finished: $url")
                    // 后备：如果 load 事件未触发初始化，这里再调一次
                    view?.evaluateJavascript(
                        """if (typeof pannellum !== 'undefined' && !viewer && window.__panoramaBase64) {
                            window.__loadPanoramaFromBase64();
                        }""",
                        null,
                    )
                }
            }
        }
    }

    LaunchedEffect(htmlPath) {
        htmlPath?.let { path ->
            webView.post {
                webView.loadUrl(path)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.destroy()
            htmlPath?.let { path ->
                try {
                    val dir = File(path.removePrefix("file://").removeSuffix("/index.html"))
                    if (dir.exists()) {
                        dir.deleteRecursively()
                    }
                } catch (e: Exception) {
                    Log.w("PanoramaViewer", "Failed to cleanup temp files", e)
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

        // 错误提示覆盖层（资源加载失败时显示）
        loadError?.let { errorMsg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    text = errorMsg,
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }

        // 加载中提示（htmlPath 还没准备好且没有错误时）
        if (htmlPath == null && loadError == null && imageUrl.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
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
