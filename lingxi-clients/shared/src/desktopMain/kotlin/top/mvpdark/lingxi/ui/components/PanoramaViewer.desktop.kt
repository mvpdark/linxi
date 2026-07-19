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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import lingxi_clients.shared.generated.resources.Res
import top.mvpdark.lingxi.core.util.PlatformLogger
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop 全景查看器（内嵌 JavaFX WebView 方案）。
 *
 * 工作流程：
 * 1. 将 pannellum.js、pannellum.css、panorama_data.js（base64 图片）写入临时目录，
 *    生成 index.html 引用上述文件（与旧方案共用 preparePanoramaFiles）
 * 2. 全局一次性初始化 JavaFX 工具包（Platform.startup + setImplicitExit(false)）
 * 3. 通过 SwingPanel 内嵌 JFXPanel，在 JavaFX Application Thread 上创建
 *    WebView + WebEngine（支持 WebGL，可拖拽交互 360° 全景）
 * 4. WebEngine 加载 file:/// URL，监听 LoadWorker 状态驱动 Compose 加载/错误 UI
 *
 * 降级策略：若 JavaFX 初始化失败（依赖缺失、原生库加载失败等），
 * 自动回退为「系统默认浏览器打开」方案（旧行为）。
 *
 * WebGL 说明：Pannellum 渲染等距柱状全景（equirectangular）必须依赖 WebGL。
 * 官方 OpenJFX 构建的 WebView 未启用 WebGL（JDK-8089881 长期未解决，
 * 实测 21.0.4 / WebKit 617.1 下 getContext('webgl') 返回 null），
 * 因此页面加载成功后会在 FX 线程主动探测 WebGL；不可用时展示明确错误
 * 并引导用户改用系统浏览器（具备完整 WebGL），避免出现黑屏静默失败。
 *
 * 线程模型：
 * - 文件准备：Dispatchers.IO
 * - JFXPanel 创建：Compose 组合线程（Desktop 上即 AWT EDT）
 * - WebView/Scene/Engine 操作：一律 Platform.runLater 到 JavaFX Application Thread
 * - LoadWorker 状态回调在 FX 线程触发，直接写 Compose snapshot state 是线程安全的
 *
 * 注意：pannellum.js 包含 $a 等 JS 变量名，不能内联到 Kotlin """...""" 模板字符串
 * （Kotlin 会将 $a 解析为模板表达式导致编译错误），必须作为独立文件通过
 * <script src="pannellum.js"> 引用。
 *
 * @param imageUrl 全景图 URL（data URL、http(s) URL、file:// 或本地路径）
 * @param modifier 修饰符
 */
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    var status by remember { mutableStateOf(PanoramaStatus.Preparing) }
    var error by remember { mutableStateOf<String?>(null) }
    var htmlFile by remember { mutableStateOf<File?>(null) }
    var webEngine by remember { mutableStateOf<WebEngine?>(null) }
    // JavaFX 不可用时的降级标记：回退为系统浏览器打开
    var useSystemBrowser by remember { mutableStateOf(false) }
    var browserOpened by remember { mutableStateOf(false) }

    // 1. 准备全景图文件（IO 线程），然后确保 JavaFX 工具包已初始化
    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) return@LaunchedEffect
        error = null
        status = PanoramaStatus.Preparing
        browserOpened = false
        try {
            val file = withContext(Dispatchers.IO) { preparePanoramaFiles(imageUrl) }
            htmlFile = file
            // JavaFX 全局只需初始化一次；失败会抛异常 → 走 catch 降级
            ensureJavaFXInitialized()
        } catch (e: Throwable) {
            PlatformLogger.e("PanoramaViewer", "Embedded JavaFX unavailable, fallback to system browser", e)
            val file = htmlFile
            if (file != null) {
                // 文件已就绪，仅 JavaFX 失败 → 系统浏览器降级
                runCatching { openInBrowser(file) }
                    .onSuccess {
                        useSystemBrowser = true
                        browserOpened = true
                    }
                    .onFailure {
                        error = "打开全景图失败: ${it.message}"
                        status = PanoramaStatus.Error
                    }
            } else {
                error = "准备全景图失败: ${e.message}"
                status = PanoramaStatus.Error
            }
        }
    }

    // 2. WebEngine 与 HTML 文件都就绪后，在 FX 线程加载 file:/// URL
    LaunchedEffect(webEngine, htmlFile, useSystemBrowser) {
        val engine = webEngine ?: return@LaunchedEffect
        val file = htmlFile ?: return@LaunchedEffect
        if (useSystemBrowser) return@LaunchedEffect
        status = PanoramaStatus.Loading
        Platform.runLater {
            // toURI().toURL() 在 Windows 上生成正确的 file:///C:/... 形式
            engine.load(file.toURI().toURL().toExternalForm())
        }
    }

    // 3. 离开组合时停止 WebView 渲染，释放 WebKit 原生资源，并清理临时文件
    DisposableEffect(Unit) {
        onDispose {
            val engine = webEngine
            if (engine != null) {
                runCatching {
                    Platform.runLater {
                        // load(null)：取消并复位 LoadWorker，停止加载与渲染
                        engine.load(null)
                    }
                }
            }
            // 清理临时全景图目录。WebKit 释放文件句柄是异步的，稍作延迟再删除
            // 以提高 Windows 上的成功率；即使删除失败，仍由 preparePanoramaFiles
            // 注册的 JVM shutdown hook 在退出时兜底清理，不会泄漏。
            val dir = htmlFile?.parentFile
            if (dir != null) {
                thread(isDaemon = true, name = "panorama-cleanup") {
                    runCatching {
                        Thread.sleep(800)
                        dir.deleteRecursively()
                    }
                }
            }
        }
    }

    // 降级 UI：系统浏览器方案（旧行为）
    if (useSystemBrowser) {
        SystemBrowserFallback(
            imageUrl = imageUrl,
            browserOpened = browserOpened,
            error = error,
            onReopen = {
                htmlFile?.let { file ->
                    runCatching { openInBrowser(file) }
                        .onFailure { error = "重新打开失败: ${it.message}" }
                }
            },
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.background(Color.Black)) {
        // JavaFX 初始化成功且文件就绪后才内嵌 WebView
        if (htmlFile != null && error == null) {
            SwingPanel(
                factory = {
                    val panel = JFXPanel()
                    panel.background = java.awt.Color.BLACK
                    // WebView 必须在 JavaFX Application Thread 上创建
                    Platform.runLater {
                        try {
                            val webView = WebView()
                            val engine = webView.engine
                            engine.isJavaScriptEnabled = true
                            // 监听页面加载状态，驱动 Compose 侧的加载/错误覆盖层。
                            // 回调在 FX 线程触发，写 Compose snapshot state 线程安全。
                            engine.loadWorker.stateProperty().addListener(
                                ChangeListener { _, _, newState ->
                                    when (newState) {
                                        Worker.State.SUCCEEDED -> {
                                            // Pannellum 等距柱状全景必须依赖 WebGL 渲染；
                                            // 官方 OpenJFX 构建的 WebView 未启用 WebGL，
                                            // 主动探测以避免黑屏静默失败。
                                            val webglOk = runCatching {
                                                engine.executeScript(
                                                    "(function(){var c=document.createElement('canvas');" +
                                                        "return !!(c.getContext('webgl')||c.getContext('experimental-webgl'));})()",
                                                ) as? Boolean
                                            }.getOrNull() ?: false
                                            if (webglOk) {
                                                status = PanoramaStatus.Ready
                                            } else {
                                                status = PanoramaStatus.Error
                                                error = "内嵌浏览器不支持 WebGL，无法渲染 360° 全景图，请在系统浏览器中打开"
                                            }
                                        }
                                        Worker.State.FAILED -> {
                                            status = PanoramaStatus.Error
                                            error = "全景图页面加载失败"
                                        }
                                        else -> Unit
                                    }
                                },
                            )
                            // WebView 作为 Scene 根节点，会随 JFXPanel 尺寸自动缩放
                            panel.scene = Scene(webView)
                            webEngine = engine
                        } catch (t: Throwable) {
                            PlatformLogger.e("PanoramaViewer", "Create JavaFX WebView failed", t)
                            error = "初始化内嵌浏览器失败: ${t.message}"
                            status = PanoramaStatus.Error
                        }
                    }
                    panel
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 加载 / 错误状态覆盖层
        when {
            error != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = error!!,
                        color = Color(0xFFFF6B6B),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    // 有已生成的 HTML 文件时，允许用户改用系统浏览器打开
                    htmlFile?.let { file ->
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                runCatching { openInBrowser(file) }
                                    .onSuccess {
                                        error = null
                                        useSystemBrowser = true
                                        browserOpened = true
                                    }
                                    .onFailure { error = "打开系统浏览器失败: ${it.message}" }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("在系统浏览器中打开")
                        }
                    }
                }
            }
            status != PanoramaStatus.Ready -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when (status) {
                            PanoramaStatus.Preparing -> "正在准备 360° 全景图..."
                            PanoramaStatus.Loading -> "正在加载 360° 全景图..."
                            else -> "正在加载 360° 全景图..."
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 全景查看器状态机 */
private enum class PanoramaStatus {
    /** 正在写临时文件 / 初始化 JavaFX */
    Preparing,

    /** WebView 正在加载页面 */
    Loading,

    /** 页面加载完成，可交互 */
    Ready,

    /** 失败 */
    Error,
}

/**
 * 降级 UI：静态预览图 + 「已在系统浏览器中打开」提示（旧方案界面）。
 */
@Composable
private fun SystemBrowserFallback(
    imageUrl: String,
    browserOpened: Boolean,
    error: String?,
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val painter = rememberAsyncImagePainter(imageUrl)
    val state by painter.state.collectAsState()

    Box(modifier = modifier.background(Color.Black)) {
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
                        text = error,
                        color = Color(0xFFFF6B6B),
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
                        onClick = onReopen,
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

/** JavaFX 工具包是否已初始化（全局只需一次） */
private val javafxInitialized = AtomicBoolean(false)

/**
 * 初始化 JavaFX 工具包（线程安全，全局仅执行一次）。
 *
 * 必须在创建任何 JavaFX 组件（JFXPanel / WebView）之前调用成功。
 * 初始化失败（如 JavaFX 依赖缺失、原生库加载失败）会抛出异常，
 * 调用方据此降级为系统浏览器方案。
 */
private fun ensureJavaFXInitialized() {
    if (javafxInitialized.get()) return
    synchronized(javafxInitialized) {
        if (javafxInitialized.get()) return
        val latch = CountDownLatch(1)
        try {
            Platform.startup {
                // 防止最后一个 JavaFX 窗口关闭时工具包自动退出，
                // 否则后续 Platform.runLater 任务会被拒绝执行
                Platform.setImplicitExit(false)
                latch.countDown()
            }
        } catch (e: IllegalStateException) {
            // 工具包已被初始化（防御性处理，理论上不会走到）
            Platform.setImplicitExit(false)
            javafxInitialized.set(true)
            return
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw IllegalStateException("JavaFX 工具包初始化超时")
        }
        javafxInitialized.set(true)
    }
}

/**
 * 准备全景图文件目录（pannellum.js + pannellum.css + panorama_data.js + index.html）。
 *
 * 关键：pannellum.js 作为独立文件写入，不内联到 Kotlin 模板字符串，
 * 避免 $a 等 JS 变量名被 Kotlin 解析为模板表达式。
 */
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
    val jsBytes = Res.readBytes("files/panorama/pannellum.js")
    File(dir, "pannellum.js").writeBytes(jsBytes)

    // 3. 写入 pannellum.css
    val cssBytes = Res.readBytes("files/panorama/pannellum.css")
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
 * 在系统默认浏览器中打开文件（降级方案使用）。
 */
private fun openInBrowser(file: File) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(file.toURI())
    } else {
        throw UnsupportedOperationException("当前系统不支持自动打开浏览器，请手动打开: " + file.absolutePath)
    }
}
