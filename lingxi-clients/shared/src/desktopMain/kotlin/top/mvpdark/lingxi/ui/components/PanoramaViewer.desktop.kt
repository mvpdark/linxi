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
import io.github.trethore.jcefgithub.CefAppBuilder
import io.github.trethore.jcefgithub.MavenCefAppHandlerAdapter
import io.github.trethore.jcefgithub.impl.progress.ConsoleProgressHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import lingxi_clients.shared.generated.resources.Res
import top.mvpdark.lingxi.core.util.PlatformLogger
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import kotlin.concurrent.thread

/**
 * Desktop 全景查看器（内嵌 JCEF / Chromium 方案）。
 *
 * 工作流程：
 * 1. 将 pannellum.js、pannellum.css、panorama_data.js（base64 图片）写入临时目录，
 *    生成 index.html 引用上述文件（与旧方案共用 preparePanoramaFiles）
 * 2. 全局一次性初始化 JCEF（CefAppBuilder 从 classpath 提取原生库 → CefApp）
 * 3. 通过 SwingPanel 内嵌 CefBrowser 的 AWT UI 组件（窗口渲染模式，GPU 加速）
 * 4. CefBrowser 加载 file:/// URL，监听 CefLoadHandler 状态驱动 Compose 加载/错误 UI
 *
 * 降级策略：若 JCEF 初始化失败（原生库缺失、平台不支持等），
 * 自动回退为「系统默认浏览器打开」方案（旧行为）。
 *
 * WebGL 说明：Pannellum 渲染等距柱状全景（equirectangular）必须依赖 WebGL。
 * 旧 JavaFX WebView 方案的官方 OpenJFX 构建不支持 WebGL（JDK-8089881），
 * 因此改用 JCEF —— 内嵌完整 Chromium 引擎，原生支持 WebGL / GPU 加速，
 * 无需运行时探测 WebGL 可用性。
 *
 * 线程模型：
 * - 文件准备 + JCEF 初始化：Dispatchers.IO（原生库提取为 IO 密集型）
 * - CefBrowser 创建：SwingPanel factory（Desktop 上即 AWT EDT）
 * - CefLoadHandler 回调在 CEF 线程触发，写 Compose snapshot state 线程安全
 *
 * 原生库分发：
 * - jcefgithub 引导器从 classpath 的 jcef-natives-<platform> jar 中提取
 *   CEF/Chromium 原生库到 ~/.lingxi/jcef/ 目录（首次运行提取，后续复用）
 * - macOS 上自动执行 unquarantine 以避免 Gatekeeper 拦截
 * - CefAppBuilder 内部注册了 JVM shutdown hook 自动调用 CefApp.dispose()
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
    var cefBrowser by remember { mutableStateOf<CefBrowser?>(null) }
    var cefClient by remember { mutableStateOf<CefClient?>(null) }
    // JCEF 初始化完成标记：仅在 init 成功后才渲染 SwingPanel，避免 factory
    // 中 CefApp.getInstance() 在 init 完成前被调用而抛异常
    var jcefReady by remember { mutableStateOf(false) }
    // JCEF 不可用时的降级标记：回退为系统浏览器打开
    var useSystemBrowser by remember { mutableStateOf(false) }
    var browserOpened by remember { mutableStateOf(false) }

    // 1. 准备全景图文件（IO 线程），然后初始化 JCEF（IO 线程，原生库提取为 IO 密集型）
    LaunchedEffect(imageUrl) {
        if (imageUrl.isEmpty()) return@LaunchedEffect
        error = null
        status = PanoramaStatus.Preparing
        browserOpened = false
        jcefReady = false
        try {
            val file = withContext(Dispatchers.IO) { preparePanoramaFiles(imageUrl) }
            htmlFile = file
            // JCEF 全局只需初始化一次；build() 线程安全且幂等（返回同一实例）。
            // 失败会抛异常 → 走 catch 降级
            withContext(Dispatchers.IO) { ensureJcefInitialized() }
            jcefReady = true
        } catch (e: Throwable) {
            PlatformLogger.e("PanoramaViewer", "Embedded JCEF unavailable, fallback to system browser", e)
            val file = htmlFile
            if (file != null) {
                // 文件已就绪，仅 JCEF 失败 → 系统浏览器降级
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

    // 2. CefBrowser 与 HTML 文件都就绪后，加载 file:/// URL
    //    CefBrowser.loadURL 内部派发到 CEF 消息循环，可从任意线程调用
    LaunchedEffect(cefBrowser, htmlFile, useSystemBrowser) {
        val browser = cefBrowser ?: return@LaunchedEffect
        val file = htmlFile ?: return@LaunchedEffect
        if (useSystemBrowser) return@LaunchedEffect
        status = PanoramaStatus.Loading
        // toURI().toURL() 在 Windows 上生成正确的 file:///C:/... 形式
        browser.loadURL(file.toURI().toURL().toExternalForm())
    }

    // 3. 离开组合时关闭浏览器、释放 CefClient（CefApp 全局单例由 shutdown hook 释放）
    DisposableEffect(Unit) {
        onDispose {
            val browser = cefBrowser
            val client = cefClient
            if (browser != null) {
                runCatching { browser.close(false) }
            }
            if (client != null) {
                runCatching { client.dispose() }
            }
            cefBrowser = null
            cefClient = null
        }
    }

    // 4. 临时目录清理绑定到当前目录对象：htmlFile 切换（同会话换全景图）时，
    //    旧 key 的 onDispose 先执行删除旧目录，新 key 的 Effect 再启动；
    //    离开组合时清理当前目录，避免旧 panorama_* 目录泄漏。
    //    Chromium 释放文件句柄是异步的，稍作延迟再删除以提高 Windows 上的成功率；
    //    即使删除失败，仍由全局唯一的 JVM shutdown hook 在退出时兜底清理，不会泄漏。
    val panoramaDir = htmlFile?.parentFile
    DisposableEffect(panoramaDir) {
        onDispose {
            if (panoramaDir != null) {
                thread(isDaemon = true, name = "panorama-cleanup") {
                    runCatching {
                        Thread.sleep(800)
                        panoramaDir.deleteRecursively()
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
        // JCEF 初始化成功且文件就绪后才内嵌 CefBrowser
        if (jcefReady && htmlFile != null && error == null) {
            SwingPanel(
                factory = {
                    try {
                        val app = CefApp.getInstance()
                        val client = app.createClient()

                        // 监听页面加载状态，驱动 Compose 侧的加载/错误覆盖层。
                        // 回调在 CEF 线程触发，写 Compose snapshot state 线程安全。
                        client.addLoadHandler(
                            object : CefLoadHandlerAdapter() {
                                override fun onLoadingStateChange(
                                    browser: CefBrowser?,
                                    isLoading: Boolean,
                                    canGoBack: Boolean,
                                    canGoForward: Boolean,
                                ) {
                                    if (!isLoading) {
                                        // 页面加载完成。JCEF 基于 Chromium，原生支持 WebGL，
                                        // 无需像 JavaFX WebView 那样主动探测 WebGL 可用性。
                                        status = PanoramaStatus.Ready
                                    }
                                }

                                override fun onLoadError(
                                    browser: CefBrowser?,
                                    frame: CefFrame?,
                                    errorCode: CefLoadHandler.ErrorCode?,
                                    errorText: String?,
                                    failedUrl: String?,
                                ) {
                                    // 仅处理主帧错误，忽略子资源错误（如图片加载失败）
                                    if (frame?.isMain == true) {
                                        status = PanoramaStatus.Error
                                        error = "全景图页面加载失败: ${errorText ?: errorCode?.toString() ?: "未知错误"}"
                                    }
                                }
                            },
                        )

                        // 创建浏览器：windowed 渲染（isOffscreenRendered=false）
                        // 以启用 GPU 加速的 WebGL（Pannellum 全景渲染必需）
                        val browser = client.createBrowser("", false, false)
                        cefBrowser = browser
                        cefClient = client

                        // CefBrowser.getUIComponent() 返回 AWT Component（窗口渲染模式下为
                        // 重量级 Canvas），用 JPanel 包裹以设置黑色背景
                        val panel = JPanel(BorderLayout())
                        panel.background = AwtColor.BLACK
                        panel.add(browser.getUIComponent(), BorderLayout.CENTER)
                        panel
                    } catch (t: Throwable) {
                        PlatformLogger.e("PanoramaViewer", "Create JCEF browser failed", t)
                        error = "初始化内嵌浏览器失败: ${t.message}"
                        status = PanoramaStatus.Error
                        JPanel().apply { background = AwtColor.BLACK }
                    }
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

/** 全景图临时目录管理：统一放在 {java.io.tmpdir}/lingxi-panorama/ 下，全局只注册一次 JVM shutdown hook。 */
private object PanoramaTempDir {
    private val registered = AtomicBoolean(false)
    private val parentDir: File = File(
        System.getProperty("java.io.tmpdir"),
        "lingxi-panorama"
    )

    fun allocateDir(): File {
        // 首次调用时注册全局唯一的 shutdown hook，删除整个父目录
        if (registered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread {
                parentDir.deleteRecursively()
            })
        }
        val dir = File(parentDir, "panorama_${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }
}

/** 全景查看器状态机 */
private enum class PanoramaStatus {
    /** 正在写临时文件 / 初始化 JCEF */
    Preparing,

    /** CefBrowser 正在加载页面 */
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

/** JCEF 是否已初始化（全局只需一次） */
private val jcefInitialized = AtomicBoolean(false)

/**
 * 初始化 JCEF（线程安全，全局仅执行一次）。
 *
 * 使用 jcefgithub 的 CefAppBuilder：
 * 1. 从 classpath 的 jcef-natives-<platform> jar 提取 CEF/Chromium 原生库
 *    到 ~/.lingxi/jcef/（首次提取，后续复用；macOS 自动 unquarantine）
 * 2. 加载原生库（jawt / libcef / jcef）并初始化 CefApp
 * 3. CefAppBuilder 内部注册 JVM shutdown hook 自动 dispose
 *
 * 必须在创建任何 CefBrowser 之前调用成功。
 * 初始化失败（如原生库缺失、平台不支持）会抛异常，
 * 调用方据此降级为系统浏览器方案。
 *
 * 窗口渲染模式（windowless_rendering_enabled=false）：
 * 启用 GPU 硬件加速的 WebGL，Pannellum 360° 全景渲染必需。
 * 对应 CefBrowser.getUIComponent() 返回重量级 AWT Canvas，
 * 与 SwingPanel 集成方式同旧 JavaFX JFXPanel。
 */
private fun ensureJcefInitialized() {
    if (jcefInitialized.get()) return
    synchronized(jcefInitialized) {
        if (jcefInitialized.get()) return
        val installDir = File(System.getProperty("user.home"), ".lingxi" + File.separator + "jcef")
        val builder = CefAppBuilder()
        builder.setInstallDir(installDir)
        builder.setProgressHandler(ConsoleProgressHandler())
        // 窗口渲染模式（非 OSR）：启用 GPU 加速的 WebGL
        builder.getCefSettings().windowless_rendering_enabled = false
        // macOS 兼容性：必须通过 setAppHandler 设置（不能直接调 CefApp.addAppHandler）
        builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})
        // build() 线程安全且幂等：安装原生库 → 初始化 CefApp → 注册 shutdown hook
        builder.build()
        jcefInitialized.set(true)
    }
}

/**
 * 准备全景图文件目录（pannellum.js + pannellum.css + panorama_data.js + index.html）。
 *
 * 关键：pannellum.js 作为独立文件写入，不内联到 Kotlin 模板字符串，
 * 避免 $a 等 JS 变量名被 Kotlin 解析为模板表达式。
 */
private suspend fun preparePanoramaFiles(imageUrl: String): File {
    // 1. 创建临时目录（统一父目录 {tmpdir}/lingxi-panorama/panorama_<ts>，
    //    JVM shutdown hook 在 PanoramaTempDir 内全局仅注册一次，退出时删除整个父目录）
    val dir = PanoramaTempDir.allocateDir()

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
    // 临时文件清理由 PanoramaTempDir 内全局唯一的 shutdown hook 兜底（退出时
    // 递归删除整个 lingxi-panorama 父目录），无需再对单个文件调用 deleteOnExit()。
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
