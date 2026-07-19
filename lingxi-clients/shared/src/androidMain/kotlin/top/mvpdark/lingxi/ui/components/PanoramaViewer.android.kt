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
 * Android 全景查看器：用 WebView 加载本地 Pannellum HTML。
 *
 * 核心策略（修复白屏 bug）：
 * 1. 读取 pannellum.js/css/html 资源，内联到单个 HTML 字符串中
 * 2. 全景图 URL（data URL 或 http URL）作为全局变量追加到 HTML 末尾
 * 3. 页面加载完成后 onPageFinished 回调中调用 __loadPanorama
 * 4. data URL 与页面同源（about:blank），WebGL 可正常加载，不会触发跨域 tainted
 *
 * 之前的方案（data URL → 临时文件 → file:// URL）会导致：
 * - WebGL texImage2D 加载 file:// 图片被跨域安全策略阻止
 * - 即使设了 allowFileAccessFromFileURLs 也无效（WebGL 的安全策略更严格）
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(InternalResourceApi::class)
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 最终要嵌入 HTML 的图片 URL（data URL 或 http URL）
    var embeddedImageUrl by remember { mutableStateOf("") }
    // 完整的 HTML 内容（含内联 JS/CSS + 图片 URL）
    var htmlContent by remember { mutableStateOf<String?>(null) }

    // 预处理图片 URL：
    // - data URL / http URL 直接用
    // - file:// URL 读取文件转成 data URL（避免 WebGL 跨域）
    LaunchedEffect(imageUrl) {
        embeddedImageUrl = if (imageUrl.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                if (imageUrl.startsWith("file://")) {
                    try {
                        val path = imageUrl.removePrefix("file://")
                        val bytes = File(path).readBytes()
                        "data:image/png;base64," + android.util.Base64.encodeToString(
                            bytes,
                            android.util.Base64.NO_WRAP,
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("PanoramaViewer", "Failed to read file:// image", e)
                        imageUrl
                    }
                } else {
                    imageUrl
                }
            }
        } else {
            ""
        }
    }

    // 构建 HTML 内容（含内联 pannellum.js + pannellum.css + 图片 URL）
    LaunchedEffect(embeddedImageUrl) {
        if (embeddedImageUrl.isEmpty()) {
            htmlContent = null
            return@LaunchedEffect
        }
        htmlContent = withContext(Dispatchers.Default) {
            try {
                val js = readResourceBytes("files/panorama/pannellum.js").decodeToString()
                val css = readResourceBytes("files/panorama/pannellum.css").decodeToString()
                val html = readResourceBytes("files/panorama/panorama_viewer.html").decodeToString()
                val inlined = html
                    .replace(
                        """<link rel="stylesheet" href="pannellum.css">""",
                        "<style>$css</style>",
                    )
                    .replace(
                        """<script src="pannellum.js"></script>""",
                        "<script>$js</script>",
                    )
                // 在 </body> 前追加一段 script，设置图片 URL 全局变量
                // 不用字符串替换 pannellum.js 内部代码，避免误伤
                val injectScript = """
                <script>
                window.__PANO_IMAGE_URL = "${embeddedImageUrl}";
                </script>
                """.trimIndent()
                inlined.replace("</body>", "$injectScript\n</body>")
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
            // 启用硬件加速，WebGL 需要
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    android.util.Log.i("PanoramaViewer", "Page finished, loading panorama")
                    // 页面加载完成后调用 __loadPanorama
                    // 图片 URL 已通过注入的 script 设置为全局变量
                    view?.evaluateJavascript(
                        """if (window.__PANO_IMAGE_URL && typeof pannellum !== 'undefined') {
                            window.__loadPanorama(window.__PANO_IMAGE_URL);
                        }""",
                        null,
                    )
                }
            }
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

    // 生命周期：Composable 离开时销毁 WebView
    DisposableEffect(Unit) {
        onDispose {
            webView.destroy()
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
