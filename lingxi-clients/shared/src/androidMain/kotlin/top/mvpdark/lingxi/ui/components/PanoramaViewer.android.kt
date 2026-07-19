package top.mvpdark.lingxi.ui.components

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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

/**
 * Android 全景查看器：用 WebView 加载本地 Pannellum HTML。
 *
 * 资源加载策略：
 * 1. 用 [readResourceBytes] 读取 pannellum.js、pannellum.css、panorama_viewer.html
 * 2. 把 JS/CSS 内联到 HTML 中（避免相对路径在 loadDataWithBaseURL 下无法解析）
 * 3. 用 [WebView.loadDataWithBaseURL] 加载内联 HTML
 * 4. Kotlin 通过 [WebView.evaluateJavascript] 调用 `window.__loadPanorama(url)` 加载全景图
 *
 * 这样无需依赖 assets 路径，跨模块也能正常工作。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(InternalResourceApi::class)
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 预加载 HTML 内容（含内联的 pannellum.js + pannellum.css）
    var htmlContent by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        htmlContent = withContext(Dispatchers.IO) {
            try {
                val js = readResourceBytes("files/panorama/pannellum.js").decodeToString()
                val css = readResourceBytes("files/panorama/pannellum.css").decodeToString()
                // 读取 HTML 模板
                val html = readResourceBytes("files/panorama/panorama_viewer.html").decodeToString()
                // 内联 JS 和 CSS：替换 <link> 和 <script src> 为内联标签
                val inlined = html
                    .replace(
                        """<link rel="stylesheet" href="pannellum.css">""",
                        "<style>$css</style>",
                    )
                    .replace(
                        """<script src="pannellum.js"></script>""",
                        "<script>$js</script>",
                    )
                inlined
            } catch (e: Exception) {
                android.util.Log.e("PanoramaViewer", "Failed to load resources", e)
                "<html><body><p style='color:white;text-align:center;margin-top:50%'>全景查看器资源加载失败</p></body></html>"
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
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
    }

    // HTML 内容就绪后加载到 WebView
    LaunchedEffect(htmlContent) {
        htmlContent?.let { html ->
            webView.post {
                webView.loadDataWithBaseURL(
                    "about:blank",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        }
    }

    // imageUrl 变化时，通过 JS 桥接加载新全景图
    LaunchedEffect(imageUrl, htmlContent) {
        if (imageUrl.isNotEmpty() && htmlContent != null) {
            // 等待 WebView 页面加载完成
            kotlinx.coroutines.delay(500)
            // 用 JS 模板字符串传参，避免 data URL 的特殊字符转义问题
            val js = "try { window.__loadPanorama(`${imageUrl}`); } catch(e) { console.error(e); }"
            webView.post {
                webView.evaluateJavascript(js, null)
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
