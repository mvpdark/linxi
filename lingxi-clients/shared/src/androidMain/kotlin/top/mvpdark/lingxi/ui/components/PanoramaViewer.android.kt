package top.mvpdark.lingxi.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import lingxi_clients.shared.generated.resources.Res
import java.io.File

/**
 * JS 桥接对象，供 WebView 中的 panorama_viewer.html 调用以回传状态。
 *
 * 必须使用 @JavascriptInterface 注解，方法名在混淆时必须保留。
 * 已在 proguard-rules.pro 中显式保留：
 *   -keepattributes JavascriptInterface
 *   -keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
 *   -keep class top.mvpdark.lingxi.ui.components.PanoramaJsBridge { *; }
 */
class PanoramaJsBridge(
    private val onRendered: () -> Unit,
    private val onError: (String) -> Unit,
) {
    @JavascriptInterface
    fun log(msg: String) {
        // Pannellum 错误信息可能含完整 data URL（多 MB），截断避免 logcat 爆炸
        val truncated = if (msg.length > 200) msg.substring(0, 200) + "...(${msg.length} chars)" else msg
        Log.d("PanoramaJS", truncated)
    }

    @JavascriptInterface
    fun onPanoramaLoaded() = onRendered()

    @JavascriptInterface
    fun onPanoramaError(msg: String) = onError(msg)
}

/**
 * Android 全景查看器：用 WebView + Pannellum 实现 360° 球面投影。
 *
 * 核心策略（彻底重写版 —— data: URL 自包含 HTML）：
 * 旧方案（WebViewAssetLoader 把缓存目录映射为 https URL + 相对路径取图）在部分
 * 设备上 Pannellum 拿不到图，表现为纯黑矩形且无错误回调。本方案彻底放弃一切
 * 中间层（WebViewAssetLoader / file:// / panorama_data.js / 缓存目录），改为：
 *
 * 1. 全景图字节在 Kotlin 侧读取后转 Base64（NO_WRAP），拼成 data: URL；
 * 2. pannellum.js / pannellum.css 直接内联进 HTML（合计仅 ~66KB，无外部请求）；
 * 3. panorama_viewer.html 模板中的三处外部引用原位替换为内联内容，
 *    window.__panoramaConfig.url 直接写 data: URL；
 * 4. 用 loadDataWithBaseURL("https://localhost/", html, ...) 一次性加载自包含 HTML。
 *
 * 这样 Pannellum 用 Image 元素直接从 data: URL 创建纹理，不经过任何 XHR /
 * 文件请求 / 路径映射，data: URL 不受同源策略限制，从根上消除黑屏的所有来源。
 * 代价是大全景图 Base64 后 HTML 体积约放大 1.37 倍，可靠性优先，可接受。
 *
 * 保留：JS 桥（加载状态回传）、12 秒看门狗（大图纹理上传耗时长）、半透明覆盖层。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var htmlContent by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // 0=未加载 1=加载中 2=渲染成功 -1=出错
    var renderState by remember { mutableStateOf(0) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) {
            htmlContent = null
            loadError = null
            renderState = 0
            return@LaunchedEffect
        }
        loadError = null
        renderState = 0
        htmlContent = withContext(Dispatchers.IO) {
            try {
                // 1. 读取全景图字节流（支持 data: / file:// / http(s):// / 裸路径）
                //    scheme 匹配忽略大小写（RFC 3986 scheme 不区分大小写）
                val imageBytes: ByteArray = when {
                    imageUrl.startsWith("data:", ignoreCase = true) -> {
                        val base64Data = imageUrl.substringAfter("base64,")
                        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    }
                    imageUrl.startsWith("file://", ignoreCase = true) -> {
                        // file:// 路径可能含 %XX 编码（空格等），用 URI 解码；
                        // 不能用 URLDecoder（会把 '+' 误转为空格）；非法字符时回退原样
                        val path = try {
                            java.net.URI(imageUrl).path ?: imageUrl.removePrefix("file://")
                        } catch (_: Exception) {
                            imageUrl.removePrefix("file://")
                        }
                        File(path).readBytes()
                    }
                    imageUrl.startsWith("http://", ignoreCase = true) ||
                        imageUrl.startsWith("https://", ignoreCase = true) -> {
                        downloadWithRedirects(imageUrl)
                    }
                    else -> {
                        // 裸路径（无 scheme）按本地文件读取；失败必须抛出并由外层 catch
                        // 设置 loadError —— 静默返回 null 会表现为无提示黑屏
                        try {
                            File(imageUrl).readBytes()
                        } catch (e: Exception) {
                            throw java.io.IOException("全景图文件读取失败", e)
                        }
                    }
                }

                // 体积上限：bytes→base64→dataUrl→html 链路瞬时内存放大 4~5 倍，
                // 低端机易 OOM；后端生成图为 2048×1024 PNG（通常 2~6MB），16MB 足够宽裕
                if (imageBytes.size > 16 * 1024 * 1024) {
                    throw java.io.IOException("全景图过大（${imageBytes.size / 1024 / 1024}MB），超过 16MB 上限")
                }

                // 2. MIME 以魔数嗅探为准（不信声明与扩展名）：
                //    声明格式与实际内容不匹配会导致部分 WebView 解码失败
                val mime: String = when {
                    imageBytes.size >= 4 && imageBytes[0] == 0x89.toByte() &&
                        imageBytes[1] == 0x50.toByte() && imageBytes[2] == 0x4E.toByte() &&
                        imageBytes[3] == 0x47.toByte() -> "image/png" // \x89PNG
                    imageBytes.size >= 12 && imageBytes[8] == 0x57.toByte() &&
                        imageBytes[9] == 0x45.toByte() && imageBytes[10] == 0x42.toByte() &&
                        imageBytes[11] == 0x50.toByte() -> "image/webp" // RIFF....WEBP
                    else -> "image/jpeg" // JPEG（FF D8 FF）及未知格式兜底
                }

                // 3. Base64（NO_WRAP：换行符会破坏 data URL 与 JS 字符串字面量）
                //    base64 字符集 A-Za-z0-9+/= 不含引号/反斜杠，可安全嵌入 JS 字符串
                val base64 = android.util.Base64.encodeToString(
                    imageBytes, android.util.Base64.NO_WRAP
                )
                val dataUrl = "data:$mime;base64,$base64"

                // 4. 读取模板与静态资源，构建自包含 HTML：
                //    三处外部引用原位替换为内联内容，页面加载后零网络/文件请求。
                //    pannellum.js 已确认不含 "</script" 子串，内联安全。
                val css = Res.readBytes("files/panorama/pannellum.css").decodeToString()
                val js = Res.readBytes("files/panorama/pannellum.js").decodeToString()
                val template =
                    Res.readBytes("files/panorama/panorama_viewer.html").decodeToString()
                val html = template
                    .replace(
                        "<link rel=\"stylesheet\" href=\"pannellum.css\">",
                        "<style>\n$css\n</style>"
                    )
                    .replace(
                        "<script src=\"panorama_data.js\"></script>",
                        "<script>\nwindow.__panoramaConfig = {url: \"$dataUrl\"};\n</script>"
                    )
                    .replace(
                        "<script src=\"pannellum.js\"></script>",
                        "<script>\n$js\n</script>"
                    )
                if (html.contains("src=\"pannellum.js\"") || html.contains("src=\"panorama_data.js\"")) {
                    // 模板结构被改动导致替换未生效时留下日志，便于 chrome://inspect 排查
                    Log.w("PanoramaViewer", "HTML template placeholders not fully replaced")
                }
                Log.i("PanoramaViewer", "Built self-contained html: ${html.length} chars, image ${imageBytes.size} bytes, mime=$mime")
                html
            } catch (e: Exception) {
                Log.e("PanoramaViewer", "Failed to build panorama html", e)
                // Compose 状态在主线程写入，避免 IO 线程与重组时序竞争
                withContext(Dispatchers.Main) {
                    loadError = "全景图加载失败: ${e.message}"
                }
                null
            }
        }
    }

    // 看门狗：12 秒内未收到 onPanoramaLoaded 则提示超时
    // （大全景图纹理上传可能超过 8 秒，8 秒阈值容易误报超时）
    LaunchedEffect(htmlContent) {
        if (htmlContent != null) {
            renderState = 1 // 加载中
            delay(12000)
            if (isActive && renderState == 1) {
                renderState = -1
                loadError = "全景加载超时，请检查设备是否支持 WebGL"
            }
        }
    }

    // WebView 销毁标志：onDispose 后置位，webView.post 的排队任务据此丢弃，
    // 避免在已 destroy 的 WebView 上执行加载导致崩溃
    val webViewDestroyed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            // 初始背景设为黑色：WebView 创建时 URL 为空，默认白底会在 HTML 加载前闪白屏；
            // 容器 Box 背景同为黑色，加载指示器改为半透明后 WebView 露出时视觉一致
            setBackgroundColor(android.graphics.Color.BLACK)

            // 启用 WebView 调试（chrome://inspect）：按应用可调试标志动态开启，
            // 仅 debuggable 构建生效，release 包关闭
            val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            WebView.setWebContentsDebuggingEnabled(debuggable)

            // JS 桥：HTML 中的 panorama_viewer.html 通过 window.AndroidBridge 回传加载结果
            // 注意：@JavascriptInterface 回调运行在 WebView 私有 JavaBridge 线程，
            // 必须切回主线程再写 Compose 状态，保证状态写入与重组时序正确。
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            addJavascriptInterface(
                PanoramaJsBridge(
                    onRendered = {
                        mainHandler.post {
                            renderState = 2
                            // W6：看门狗超时后迟到的加载成功也要清除错误遮罩，
                            // 否则错误层会永久盖在正常渲染的全景上
                            loadError = null
                        }
                    },
                    onError = { msg ->
                        mainHandler.post {
                            renderState = -1
                            loadError = msg
                        }
                    }
                ),
                "AndroidBridge"
            )

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    // consoleMessage.message() 可能含完整 data URL（多 MB），截断避免 logcat 爆炸
                    val msg = consoleMessage.message()
                    val truncated = if (msg.length > 200) msg.substring(0, 200) + "...(${msg.length} chars)" else msg
                    Log.d(
                        "PanoramaJS",
                        "[${consoleMessage.messageLevel()}] $truncated (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i("PanoramaViewer", "Page finished: $url")
                }
            }
        }
    }

    LaunchedEffect(htmlContent) {
        val html = htmlContent
        if (html != null) {
            webView.post {
                if (webViewDestroyed.get()) return@post
                // 自包含 HTML 一次性加载：全景图为 data: URL，JS/CSS 均已内联，
                // base URL 仅作占位（页面不发起任何相对路径请求），
                // data: URL 不受同源策略限制，Pannellum 可直接创建纹理。
                webView.loadDataWithBaseURL(
                    "https://localhost/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        } else {
            // imageUrl 清空时重置 WebView，避免残留显示上一张全景图
            webView.post {
                if (webViewDestroyed.get()) return@post
                webView.loadUrl("about:blank")
            }
        }
    }

    // WebView 销毁：只执行一次（webView 由 remember 持有，只允许 destroy 一次）
    DisposableEffect(Unit) {
        onDispose {
            webViewDestroyed.set(true)
            // 先加载空白页停止所有进行中的加载/定时器，再执行 JS 清理 + destroy，
            // 避免 Pannellum 的 WebGL 上下文泄漏及 destroy 时的回调崩溃
            try { webView.loadUrl("about:blank") } catch (_: Exception) { }
            try {
                webView.evaluateJavascript("if(typeof __destroyPanorama==='function') __destroyPanorama();", null)
            } catch (_: Exception) { }
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

        // 错误提示覆盖层：半透明红色叠加而非纯黑全覆盖，
        // HTML 内 #hint 的错误详情（如 WebGL 不可用）仍可被用户看到
        loadError?.let { errorMsg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = errorMsg,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }

        // 加载中提示（HTML 已准备好但 JS 还没回调成功）：
        // 半透明叠加层而非纯黑全覆盖，WebView 保持可见，
        // 用户能看到全景从黑屏逐渐渲染出来的过程
        if (htmlContent != null && renderState != 2 && loadError == null && imageUrl.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
    }
}

/**
 * 下载字节流并手动跟随重定向（最多 5 跳）。
 *
 * HttpURLConnection 默认虽自动跟随重定向，但**不跟随跨协议跳转**（http↔https），
 * 这里显式逐跳处理并校验最终状态码，避免 3xx 错误页/200 错误页字节被当作全景图。
 */
private fun downloadWithRedirects(url: String, redirectsLeft: Int = 5): ByteArray {
    if (redirectsLeft <= 0) throw java.io.IOException("重定向次数过多")
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        useCaches = false
        instanceFollowRedirects = false
    }
    try {
        return when (val code = conn.responseCode) {
            java.net.HttpURLConnection.HTTP_OK ->
                conn.inputStream.use { it.readBytes() }
            java.net.HttpURLConnection.HTTP_MOVED_PERM,
            java.net.HttpURLConnection.HTTP_MOVED_TEMP,
            java.net.HttpURLConnection.HTTP_SEE_OTHER,
            307, 308 -> {
                val location = conn.getHeaderField("Location")
                    ?: throw java.io.IOException("HTTP $code 响应缺少 Location")
                // Location 可能是相对路径，基于当前 URL 解析
                val next = java.net.URL(java.net.URL(url), location).toString()
                downloadWithRedirects(next, redirectsLeft - 1)
            }
            else -> throw java.io.IOException("HTTP $code")
        }
    } finally {
        conn.disconnect()
    }
}
