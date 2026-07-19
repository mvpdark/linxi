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
 * 资源加载策略：
 * 1. 用 [readResourceBytes] 读取 pannellum.js、pannellum.css、panorama_viewer.html
 * 2. 把 JS/CSS 内联到 HTML 中（避免相对路径在 loadDataWithBaseURL 下无法解析）
 * 3. 用 [WebView.loadDataWithBaseURL] 加载内联 HTML
 * 4. Kotlin 通过 [WebView.evaluateJavascript] 调用 `window.__loadPanorama(url)` 加载全景图
 *
 * 图片 URL 处理策略（修复 data URL 过长导致 evaluateJavascript 静默失败）：
 * - data URL（base64）可能高达 2-7MB，直接通过 evaluateJavascript 字符串插值传参
 *   会导致 IPC 传输失败或 V8 解析超时
 * - 解决方案：先把 base64 data URL 解码写入缓存临时文件，用 file:// URL 传给 Pannellum
 * - file:// URL 只有几十个字符，evaluateJavascript 不会失败
 *
 * 页面加载时序（修复 500ms 固定延时竞态条件）：
 * - 用 [WebViewClient.onPageFinished] 回调替代固定延时
 * - 只有页面真正加载完成后才调用 __loadPanorama
 *
 * 生命周期管理（修复 WebView 内存泄漏）：
 * - [DisposableEffect] 在 Composable 离开时销毁 WebView 并清理临时文件
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
    // 页面是否已加载完成（onPageFinished 回调）
    var pageLoaded by remember { mutableStateOf(false) }
    // 转换后的本地图片 URL（data URL → file:// URL）
    var localImageUrl by remember { mutableStateOf("") }

    // 预加载 HTML 内容
    LaunchedEffect(Unit) {
        htmlContent = withContext(Dispatchers.IO) {
            try {
                val js = readResourceBytes("files/panorama/pannellum.js").decodeToString()
                val css = readResourceBytes("files/panorama/pannellum.css").decodeToString()
                val html = readResourceBytes("files/panorama/panorama_viewer.html").decodeToString()
                html
                    .replace(
                        """<link rel="stylesheet" href="pannellum.css">""",
                        "<style>$css</style>",
                    )
                    .replace(
                        """<script src="pannellum.js"></script>""",
                        "<script>$js</script>",
                    )
            } catch (e: Exception) {
                android.util.Log.e("PanoramaViewer", "Failed to load resources", e)
                "<html><body><p style='color:white;text-align:center;margin-top:50%'>全景查看器资源加载失败</p></body></html>"
            }
        }
    }

    // 把 data URL 写入临时文件，避免 evaluateJavascript 传超大字符串
    LaunchedEffect(imageUrl) {
        pageLoaded = false  // 重置页面加载状态
        if (imageUrl.isNotEmpty()) {
            localImageUrl = withContext(Dispatchers.IO) {
                if (imageUrl.startsWith("data:")) {
                    try {
                        // 解析 data URL：data:image/png;base64,xxxx
                        val base64Data = imageUrl.substringAfter("base64,")
                        val bytes = android.util.Base64.decode(
                            base64Data,
                            android.util.Base64.DEFAULT,
                        )
                        // 写入缓存目录的临时文件
                        val tempFile = File(
                            context.cacheDir,
                            "panorama_temp_${System.currentTimeMillis()}.png",
                        )
                        tempFile.writeBytes(bytes)
                        android.util.Log.i(
                            "PanoramaViewer",
                            "Data URL → temp file: ${tempFile.absolutePath} (${bytes.size} bytes)",
                        )
                        "file://${tempFile.absolutePath}"
                    } catch (e: Exception) {
                        android.util.Log.e("PanoramaViewer", "Failed to write temp file", e)
                        imageUrl  // 回退到原始 URL（远程 URL 仍可用）
                    }
                } else {
                    // 非 data URL（http/https），直接使用
                    imageUrl
                }
            }
        } else {
            localImageUrl = ""
        }
    }

    // WebView 只创建一次，配置 onPageFinished 回调
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            // 修复白屏 bug：允许 file:// URL 的跨域访问
            // Pannellum 的 WebGL 通过 texImage2D 加载 file:// 临时图片时，
            // 若 WebView origin 为 null（about:blank）会被跨域安全策略阻止
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            // 用 onPageFinished 回调替代固定延时，确保页面真正加载完成
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                    android.util.Log.i("PanoramaViewer", "Page finished loading: $url")
                }
            }
        }
    }

    // HTML 内容就绪后加载到 WebView
    LaunchedEffect(htmlContent) {
        htmlContent?.let { html ->
            webView.post {
                webView.loadDataWithBaseURL(
                    // 修复白屏 bug：用 file:// baseURL 使页面与临时图片同源
                    // about:blank 会让 WebView origin 为 null，WebGL 加载 file:// 图片时
                    // 触发跨域安全策略，texImage2D 抛 SecurityError 导致白屏
                    "file://${context.cacheDir.absolutePath}/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        }
    }

    // 页面加载完成 + 本地图片 URL 就绪 → 加载全景图
    // 只有两个条件同时满足才执行，避免竞态条件
    LaunchedEffect(localImageUrl, pageLoaded) {
        if (localImageUrl.isNotEmpty() && pageLoaded) {
            val js = "try { window.__loadPanorama(`${localImageUrl}`); } catch(e) { console.error('Panorama load error:', e); }"
            webView.post {
                webView.evaluateJavascript(js, null)
                android.util.Log.i("PanoramaViewer", "Called __loadPanorama with URL length=${localImageUrl.length}")
            }
        }
    }

    // 生命周期：Composable 离开时销毁 WebView 并清理临时文件
    DisposableEffect(Unit) {
        onDispose {
            // 清理缓存目录下的全景图临时文件
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("panorama_temp_")) {
                    file.delete()
                }
            }
            // 销毁 WebView 避免内存泄漏
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
