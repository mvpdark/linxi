package top.mvpdark.lingxi.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import lingxi_clients.shared.generated.resources.Res
import java.io.File
import java.io.FileInputStream

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
    fun log(msg: String) = Log.d("PanoramaJS", msg)

    @JavascriptInterface
    fun onPanoramaLoaded() = onRendered()

    @JavascriptInterface
    fun onPanoramaError(msg: String) = onError(msg)
}

/**
 * Android 全景查看器：用 WebView + Pannellum 实现 360° 球面投影。
 *
 * 核心策略（v1.0.77 后的彻底修复）：
 * 1. 把 pannellum.js、pannellum.css、panorama_viewer.html 写到应用缓存目录
 * 2. 把全景图也原样写入同一缓存目录（panorama.{png|jpg|webp}，扩展名如实反映内容格式）
 * 3. panorama_data.js 改为只写相对路径配置（window.__panoramaConfig = {"url":"panorama.png"}）
 * 4. 用 androidx.webkit.WebViewAssetLoader 把缓存目录映射为 https://appassets.androidplatform.net/panorama/...
 *    WebView 加载 https 同源 URL，Pannellum 内部 XHR 取图行为在所有 Android WebView 版本上一致，
 *    彻底绕开 file:// + XHR responseType="blob" 在 API 29+ 返回 null 的问题。
 *    注意：AssetLoader 对二进制资源必须传 encoding=null，仅文本（js/css/html）传 UTF-8，
 *    否则 WebView 对图片字节流做文本规范化 → 解码失败 → 黑屏/loadError。
 * 5. 去掉 base64 → blob URL 中间层，不再在 JS 层做 atob 解码（低端机 atob + Uint8Array
 *    构造会瞬时把内存放大 4~5 倍导致 OOM 或 WebView 崩溃）。
 * 6. 全屏按钮隐藏（showFullscreenCtrl: false）：Android WebView 未实现 onShowCustomView，
 *    用户点击全屏按钮无反应甚至卡死。
 * 7. JS 桥 + 看门狗：HTML 在 load/error/loadError 时回调 Kotlin，Kotlin 据此更新 UI 状态，
 *    同时以 12 秒看门狗兜底（大全景图纹理上传耗时较长，8 秒阈值易误报），
 *    超时则显示「全景加载超时」。
 * 8. WebGL 探测：HTML 入口处主动探测 canvas.getContext('webgl')，不支持时立即显示中文提示。
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
    // 0=未加载 1=加载中 2=渲染成功 -1=出错
    var renderState by remember { mutableStateOf(0) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) {
            htmlPath = null
            loadError = null
            renderState = 0
            return@LaunchedEffect
        }
        loadError = null
        renderState = 0
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

                // 3. 写入 index.html（纯模板，不再内含 base64）
                val htmlTemplate = Res.readBytes("files/panorama/panorama_viewer.html").decodeToString()
                File(dir, "index.html").writeText(htmlTemplate)

                // 4. 全景图 → 写入本地文件（保留原始格式扩展名，避免 MIME 与内容不匹配）
                //    data URL 声明的 MIME 可能是 png/jpeg/webp，必须如实保留；
                //    旧代码无论格式一律命名为 panorama.jpg 并以 image/jpeg 提供，
                //    部分 WebView 版本严格按 Content-Type 解码 → PNG 字节流解码失败 → Pannellum loadError。
                val imageBytes: ByteArray = when {
                    imageUrl.startsWith("data:") -> {
                        val base64Data = imageUrl.substringAfter("base64,")
                        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    }
                    imageUrl.startsWith("file://") -> {
                        File(imageUrl.removePrefix("file://")).readBytes()
                    }
                    imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                        val url = java.net.URL(imageUrl)
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        conn.useCaches = false
                        try {
                            // 校验状态码：避免 3xx 错误页/200 错误页字节被当作全景图写入（W12 同类）
                            val code = conn.responseCode
                            if (code != java.net.HttpURLConnection.HTTP_OK) {
                                throw java.io.IOException("HTTP $code")
                            }
                            conn.inputStream.use { it.readBytes() }
                        } finally {
                            conn.disconnect()
                        }
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
                val ext = when {
                    imageUrl.startsWith("data:image/png") -> "png"
                    imageUrl.startsWith("data:image/webp") -> "webp"
                    imageUrl.startsWith("data:") -> "jpg"
                    else -> {
                        // 去掉 query/fragment 再取扩展名；非法扩展名回退 jpg
                        val pathOnly = imageUrl.substringBefore('?').substringBefore('#')
                        val candidate = pathOnly.substringAfterLast('.', "").lowercase()
                        if (candidate in listOf("jpg", "jpeg", "png", "webp")) candidate else "jpg"
                    }
                }
                val imageFileName = "panorama.$ext"
                File(dir, imageFileName).writeBytes(imageBytes)

                // 5. 写入 panorama_data.js（仅写相对路径配置，文件名与上面写入的一致）
                File(dir, "panorama_data.js").writeText(
                    "window.__panoramaConfig = {url: \"$imageFileName\"};"
                )

                // 返回文件路径（后续会被 WebViewAssetLoader 映射为 https URL）
                dir.absolutePath
            } catch (e: Exception) {
                Log.e("PanoramaViewer", "Failed to prepare panorama files", e)
                loadError = "全景图加载失败: ${e.message}"
                null
            }
        }
    }

    // 看门狗：12 秒内未收到 onPanoramaLoaded 则提示超时
    // （大全景图纹理上传可能超过 8 秒，8 秒阈值容易误报超时）
    LaunchedEffect(htmlPath) {
        if (htmlPath != null) {
            renderState = 1 // 加载中
            delay(12000)
            if (isActive && renderState == 1) {
                renderState = -1
                loadError = "全景加载超时，请检查设备是否支持 WebGL"
            }
        }
    }

    // WebViewAssetLoader：把缓存目录映射为 https://appassets.androidplatform.net/panorama/...
    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/panorama/", object : WebViewAssetLoader.PathHandler {
                override fun handle(path: String): WebResourceResponse? {
                    // path 去掉前缀后形如 "panorama_1234567890/panorama.png"
                    return try {
                        val file = File(context.cacheDir, path)
                        if (!file.exists() || !file.isFile) {
                            Log.w("PanoramaViewer", "AssetLoader miss: $path")
                            return null
                        }
                        // 关键：encoding 仅对文本类型有意义，二进制（图片）必须为 null。
                        // 传 "UTF-8" 会让 WebView 对二进制响应做文本规范化处理，
                        // 导致 PNG/JPEG 字节流被破坏、Pannellum 解码失败（黑屏/loadError）。
                        val mime: String
                        val encoding: String?
                        when (file.extension.lowercase()) {
                            "js" -> { mime = "application/javascript"; encoding = "UTF-8" }
                            "css" -> { mime = "text/css"; encoding = "UTF-8" }
                            "html" -> { mime = "text/html"; encoding = "UTF-8" }
                            "jpg", "jpeg" -> { mime = "image/jpeg"; encoding = null }
                            "png" -> { mime = "image/png"; encoding = null }
                            "webp" -> { mime = "image/webp"; encoding = null }
                            else -> { mime = "application/octet-stream"; encoding = null }
                        }
                        WebResourceResponse(
                            mime,
                            encoding,
                            FileInputStream(file)
                        )
                    } catch (e: Exception) {
                        Log.w("PanoramaViewer", "AssetLoader failed for $path", e)
                        null
                    }
                }
            })
            .build()
    }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            // 收窄 file:// XHR 权限：所有内容已经 WebViewAssetLoader 走
            // https://appassets.androidplatform.net 同源加载，不需要 file:// XHR 权限
            // （allowFileAccess / allowContentAccess 保持 true 不动）
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
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
                    Log.d(
                        "PanoramaJS",
                        "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    // WebViewAssetLoader.shouldInterceptRequest 仅接受 Uri 重载，
                    // 传 String 会编译失败（1.x 起 String 重载已移除）
                    val uri = request?.url ?: return null
                    return assetLoader.shouldInterceptRequest(uri)
                        ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i("PanoramaViewer", "Page finished: $url")
                }
            }
        }
    }

    LaunchedEffect(htmlPath) {
        val dirPath = htmlPath
        if (dirPath != null) {
            webView.post {
                // WebViewAssetLoader 把缓存目录映射为 https://appassets.androidplatform.net/panorama/...
                // 入口文件是缓存目录里的 index.html（由 panorama_viewer.html 模板写入）
                webView.loadUrl("https://appassets.androidplatform.net/panorama/${File(dirPath).name}/index.html")
            }
        } else {
            // imageUrl 清空时重置 WebView，避免残留显示上一张全景图
            webView.post { webView.loadUrl("about:blank") }
        }
    }

    // WebView 销毁：只执行一次（webView 由 remember 持有，只允许 destroy 一次）
    DisposableEffect(Unit) {
        onDispose {
            // 销毁 WebView 前先调用 JS 清理，避免 Pannellum 的 WebGL 上下文泄漏
            try {
                webView.evaluateJavascript("if(typeof __destroyPanorama==='function') __destroyPanorama();", null)
            } catch (_: Exception) { }
            webView.destroy()
        }
    }

    // 临时目录清理：按 key 清理。htmlPath 每次变化时，旧 key 的 onDispose 先执行删除旧目录，
    // 新 key 的 Effect 再启动；离开组合时清理当前目录。
    // 避免同一会话多次切换全景图时旧 panorama_* 目录永不删除导致的泄漏。
    DisposableEffect(htmlPath) {
        val currentPath = htmlPath
        onDispose {
            currentPath?.let { path ->
                try {
                    File(path).deleteRecursively()
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

        // 加载中提示（htmlPath 已准备好但 JS 还没回调成功）：
        // 半透明叠加层而非纯黑全覆盖，WebView 保持可见，
        // 用户能看到全景从黑屏逐渐渲染出来的过程
        if (htmlPath != null && renderState != 2 && loadError == null && imageUrl.isNotEmpty()) {
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
