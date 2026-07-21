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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
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
 * 1. 将共享资源 panorama_viewer.html（原样 → index.html）、pannellum.js、
 *    pannellum.css、全景图原文件（panorama.{png|jpg|webp}）写入临时目录，
 *    panorama_data.js 仅写相对路径配置 window.__panoramaConfig.url ——
 *    与 Android 端策略一致；不再 base64 内联图片 + blob URL（避免大图
 *    atob 解码瞬时放大 4~5 倍内存）
 * 2. 全局一次性初始化 JCEF（CefAppBuilder 从 classpath 提取原生库 → CefApp），
 *    并附加 allow-file-access-from-files 等命令行开关以放开 file:// 图片加载
 * 3. 通过 SwingPanel 内嵌 CefBrowser 的 AWT UI 组件（窗口渲染模式，GPU 加速）
 * 4. CefBrowser 加载 file:/// URL，监听 CefLoadHandler 状态驱动 Compose 加载/错误 UI
 *
 * 平台策略：Windows / Linux 使用内嵌 JCEF；macOS 不支持 JCEF windowed
 * 渲染（createBrowser 必失败），直接走「系统默认浏览器打开」降级方案。
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
 * - CefBrowser 创建：LaunchedEffect（EDT），SwingPanel 仅包裹其 UI 组件
 * - CefLoadHandler 回调在 CEF 线程触发，写 Compose snapshot state 线程安全
 *
 * 原生库分发：
 * - jcefgithub 引导器从 classpath 的 jcef-natives-<platform> jar 中提取
 *   CEF/Chromium 原生库到 ~/.lingxi/jcef/ 目录（首次运行提取，后续复用）
 * - macOS 上自动执行 unquarantine 以避免 Gatekeeper 拦截
 * - CefAppBuilder 内部注册了 JVM shutdown hook 自动调用 CefApp.dispose()
 *
 * 注意：panorama_viewer.html 与 pannellum.js 均含 JS 代码（$a 等变量名），
 * 通过 Res.readBytes() 原样写盘，不经过 Kotlin """...""" 模板字符串
 * （避免 $a 被 Kotlin 解析为模板表达式），无转义问题。
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
        // 清空上一张全景图的文件引用：否则新图 prepare 失败时，catch 分支会拿到
        // 旧 htmlFile 并把「旧全景图」降级到系统浏览器打开（内容错配）
        htmlFile = null
        try {
            // 看门狗：http 下载慢/挂起时 60 秒超时，走 catch 错误处理
            val file = withContext(Dispatchers.IO) {
                withTimeout(60_000) { preparePanoramaFiles(imageUrl) }
            }
            htmlFile = file
            // macOS 不支持 JCEF windowed 渲染（createBrowser 必失败），
            // 跳过 ensureJcefInitialized（避免无谓的原生库提取与 25s 看门狗等待），
            // 直接走系统浏览器降级路径
            if (isMacOs()) {
                runCatching { openInBrowser(file) }
                    .onSuccess {
                        useSystemBrowser = true
                        browserOpened = true
                    }
                    .onFailure {
                        error = "打开全景图失败: ${it.message}"
                        status = PanoramaStatus.Error
                    }
                return@LaunchedEffect
            }
            // JCEF 全局只需初始化一次；build() 线程安全且幂等（返回同一实例）。
            // 失败会抛异常 → 走 catch 降级
            // JCEF 初始化（首次需提取原生库）设 120s 超时：防止原生库提取卡住
            // 导致看门狗（25s）超时后误判为 WebGL 不支持
            withTimeout(120_000) {
                withContext(Dispatchers.IO) { ensureJcefInitialized() }
            }
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

    // 2. JCEF 就绪 + HTML 文件就绪后，创建 CefBrowser 并直接加载 file:/// URL
    //    浏览器在 LaunchedEffect（EDT）中创建，不在 SwingPanel factory 中创建，
    //    这样可以在 status != Ready 时不渲染 SwingPanel（避免重量级 AWT Canvas
    //    的 z-order 遮挡 Compose 覆盖层），等 Pannellum load 事件（纹理上传完成）
    //    通过 JS 桥回调后再渲染 SwingPanel。
    LaunchedEffect(jcefReady, htmlFile, useSystemBrowser) {
        if (!jcefReady || useSystemBrowser) {
            // 降级到系统浏览器时（用户在错误覆盖层点击「在系统浏览器中打开」），
            // 关闭仍在后台运行的内嵌浏览器，避免 WebGL 渲染空转 + 文件句柄占用
            // 导致临时目录无法删除。
            // 无条件执行（不再仅限 useSystemBrowser）：切换图片时若新图 prepare
            // 失败（jcefReady 被重置为 false），旧 CefBrowser/CefClient 也必须
            // 关闭释放，否则永久泄漏
            cefBrowser?.let { runCatching { it.close(true) } }
            cefClient?.let { runCatching { it.dispose() } }
            cefBrowser = null
            cefClient = null
            return@LaunchedEffect
        }
        val file = htmlFile ?: return@LaunchedEffect

        // 关闭旧浏览器（切换全景图时 htmlFile 变化触发）。
        // close(true) 强制关闭，跳过 beforeunload 等待，避免与随后的 dispose() 竞态
        cefBrowser?.let { runCatching { it.close(true) } }
        cefClient?.let { runCatching { it.dispose() } }
        cefBrowser = null
        cefClient = null

        status = PanoramaStatus.Loading
        error = null
        // client 提升为可空局部变量：createBrowser 抛异常时 catch 分支需 dispose 防泄漏
        var client: CefClient? = null
        try {
            val app = CefApp.getInstance()
            client = app.createClient()

            // JS 桥：CefMessageRouter 接收页面 cefQuery 调用。
            // 共享模板通过 window.AndroidBridge.onPanoramaLoaded() 回传加载完成，
            // 垫片（ANDROID_BRIDGE_SHIM_JS）把调用转为 cefQuery({request:'loaded'})，
            // 此处接收并设置 Ready。CefMessageRouter 在 V8 context 创建时自动注入
            // window.cefQuery，早于页面脚本执行。
            val router = CefMessageRouter.create()
            router.addHandler(object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    queryId: Long,
                    request: String?,
                    persistent: Boolean,
                    callback: CefQueryCallback?,
                ): Boolean {
                    // 旧浏览器的回调可能在新浏览器创建后仍触发（close/dispose 异步），
                    // 校验传入 browser 是否仍为当前活跃浏览器，避免覆盖新状态
                    val active = cefBrowser
                    if (browser != null && active != null && browser !== active) {
                        callback?.success("")
                        return true
                    }
                    when (request) {
                        "loaded" -> {
                            // Pannellum load 事件 = 纹理上传完成，真正可交互
                            status = PanoramaStatus.Ready
                            error = null
                        }
                        null -> return false
                        else -> {
                            when {
                                request.startsWith("error:") -> {
                                    status = PanoramaStatus.Error
                                    error = request.substringAfter("error:")
                                }
                                request.startsWith("log:") -> {
                                    PlatformLogger.d("PanoramaJS", request.substringAfter("log:"))
                                }
                            }
                        }
                    }
                    callback?.success("")
                    return true
                }
            }, false)
            client.addMessageRouter(router)

            // 监听页面加载状态：onLoadStart 尽早注入垫片 + 错误处理。
            // 回调在 CEF 线程触发，写 Compose snapshot state 线程安全。
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    transitionType: org.cef.network.CefRequest.TransitionType?,
                ) {
                    // 主帧开始加载时（早于页面脚本执行与 window load 事件）注入垫片：
                    // 页面 load 阶段同步调用的 bridgeError（WebGL 不支持 / pannellum
                    // 缺失）才能被捕获；若等到 onLoadEnd 才注入，这些同步错误会丢失
                    // 并退化为 25s 超时误报。
                    // 注：当前使用的 jcefgithub fork 的 CefMessageRouterHandler 未暴露
                    // onContextCreated 回调，onLoadStart 是该 API 下最早的注入时机
                    if (frame?.isMain == true && browser != null) {
                        browser.executeJavaScript(
                            ANDROID_BRIDGE_SHIM_JS,
                            frame.url ?: "about:blank",
                            0,
                        )
                    }
                }

                override fun onLoadEnd(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    httpStatusCode: Int,
                ) {
                    // 主帧加载完成时也注入垫片（onLoadStart 已注入，此处幂等兜底）
                    if (frame?.isMain == true && browser != null) {
                        browser.executeJavaScript(
                            ANDROID_BRIDGE_SHIM_JS,
                            frame.url ?: "about:blank",
                            0,
                        )
                    }
                }

                override fun onLoadError(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?,
                ) {
                    // 仅处理主帧错误，忽略子资源错误（如图片加载失败由 Pannellum loadError 处理）
                    if (frame?.isMain == true) {
                        // 旧浏览器的 ERR_ABORTED 等回调可能在新浏览器创建后仍触发，
                        // 校验 browser 是否仍为当前活跃浏览器
                        val active = cefBrowser
                        if (browser != null && active != null && browser !== active) return
                        status = PanoramaStatus.Error
                        error = "全景图页面加载失败: ${errorText ?: errorCode?.toString() ?: "未知错误"}"
                    }
                }
            })

            // 创建浏览器并直接加载 URL（不再用空 URL + 后续 loadURL，避免白屏）
            // windowed 渲染（isOffscreenRendered=false）启用 GPU 加速的 WebGL
            val url = file.toURI().toURL().toExternalForm()
            val browser = client.createBrowser(url, false, false)
            cefBrowser = browser
            cefClient = client
        } catch (t: Throwable) {
            // createBrowser 失败时局部 client 未挂到 state，需在此 dispose 防泄漏
            runCatching { client?.dispose() }
            PlatformLogger.e("PanoramaViewer", "Create JCEF browser failed", t)
            error = "初始化内嵌浏览器失败: ${t.message}"
            status = PanoramaStatus.Error
        }
    }

    // 2b. 看门狗：25 秒内未收到 Pannellum load 回调则提示超时
    //     （大全景图纹理上传耗时较长，与 Android 端 12 秒阈值相比 Desktop 给更宽裕）
    LaunchedEffect(status) {
        if (status == PanoramaStatus.Loading) {
            delay(PANORAMA_LOAD_TIMEOUT_MS)
            if (isActive && status == PanoramaStatus.Loading) {
                status = PanoramaStatus.Error
                error = "全景加载超时，请检查设备是否支持 WebGL"
                // 关闭超时的浏览器：否则 WebGL 渲染空转 + 文件句柄占用
                // 导致临时目录无法删除
                cefBrowser?.let { runCatching { it.close(true) } }
                cefClient?.let { runCatching { it.dispose() } }
                cefBrowser = null
                cefClient = null
            }
        }
    }

    // 3. 离开组合时关闭浏览器、释放 CefClient（CefApp 全局单例由 shutdown hook 释放）
    DisposableEffect(Unit) {
        onDispose {
            val browser = cefBrowser
            val client = cefClient
            if (browser != null) {
                // close(true) 强制关闭，跳过 beforeunload 等待，避免与随后的 dispose() 竞态
                runCatching { browser.close(true) }
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
                    // 重试清理：Chromium 释放文件句柄是异步的，首次等 800ms，
                    // 后续每次 1s，最多 6 次；仍失败打日志，由 shutdown hook 兜底
                    var deleted = false
                    for (attempt in 0 until 6) {
                        runCatching { Thread.sleep(if (attempt == 0) 800L else 1000L) }
                        deleted = runCatching { panoramaDir.deleteRecursively() }
                            .getOrDefault(false) || !panoramaDir.exists()
                        if (deleted) break
                    }
                    if (!deleted) {
                        PlatformLogger.d(
                            "PanoramaViewer",
                            "临时目录清理失败（已重试 6 次），由 shutdown hook 兜底: ${panoramaDir.absolutePath}",
                        )
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
        // 仅在 Ready 且无错误时渲染 SwingPanel，避免重量级 AWT Canvas（JCEF 窗口渲染
        // 模式下的 windowed Canvas）始终渲染在所有 Compose 内容之上、遮挡加载/错误覆盖层。
        // 浏览器已在 LaunchedEffect 中创建并加载 URL，此处 factory 仅包裹其 UI 组件。
        if (jcefReady && htmlFile != null && status == PanoramaStatus.Ready && error == null) {
            cefBrowser?.let { browser ->
                SwingPanel(
                    factory = {
                        // 浏览器已在 LaunchedEffect（EDT）中创建，此处仅包裹其 UI 组件。
                        // CefBrowser.getUIComponent() 返回 AWT Component（窗口渲染模式下为
                        // 重量级 Canvas），用 JPanel 包裹以设置黑色背景
                        val panel = JPanel(BorderLayout())
                        panel.background = AwtColor.BLACK
                        panel.add(browser.getUIComponent(), BorderLayout.CENTER)
                        panel
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 加载 / 错误状态覆盖层（纯 Compose，SwingPanel 未渲染时不会被遮挡）
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
    // 目录序号：同一毫秒内连续分配（快速切换全景图）时保证目录名唯一，避免
    // 两个查看器共用同一目录互相覆盖文件
    private val sequence = java.util.concurrent.atomic.AtomicInteger(0)
    private val parentDir: File = File(
        System.getProperty("java.io.tmpdir"),
        "lingxi-panorama"
    )

    /** 残留目录判定阈值：mtime 超过 1 小时的 panorama_* 目录视为上次运行残留 */
    private const val STALE_DIR_AGE_MS = 60L * 60L * 1000L

    fun allocateDir(): File {
        // 首次调用时注册全局唯一的 shutdown hook，删除整个父目录
        if (registered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread {
                parentDir.deleteRecursively()
            })
        }
        // 顺带清扫 mtime 超过 1 小时的 panorama_* 残留目录（上次运行清理失败 /
        // 进程被杀导致 shutdown hook 未执行的残留，避免跨启动累积）
        sweepStaleDirs()
        val dir = File(
            parentDir,
            "panorama_${System.currentTimeMillis()}_${sequence.incrementAndGet()}"
        )
        dir.mkdirs()
        return dir
    }

    private fun sweepStaleDirs() {
        runCatching {
            val now = System.currentTimeMillis()
            parentDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory &&
                    dir.name.startsWith("panorama_") &&
                    now - dir.lastModified() > STALE_DIR_AGE_MS
                ) {
                    dir.deleteRecursively()
                }
            }
        }
    }
}

/** 全景查看器状态机 */
private enum class PanoramaStatus {
    /** 正在写临时文件 / 初始化 JCEF */
    Preparing,

    /** CefBrowser 正在加载页面，等待 Pannellum load 回调 */
    Loading,

    /** Pannellum load 事件已触发（纹理上传完成），可交互 */
    Ready,

    /** 失败 */
    Error,
}

/** 全景加载看门狗超时（毫秒）。大全景图纹理上传耗时较长，25 秒兜底。 */
private const val PANORAMA_LOAD_TIMEOUT_MS = 25000L

/**
 * 注入到 JCEF 页面的 AndroidBridge 兼容垫片。
 *
 * 共享模板 panorama_viewer.html 通过 window.AndroidBridge 回传状态：
 * - bridgeLog(msg) → AndroidBridge.log(msg)
 * - bridgeLoaded() → AndroidBridge.onPanoramaLoaded()
 * - bridgeError(msg) → AndroidBridge.onPanoramaError(msg)
 *
 * Android 端用 @JavascriptInterface 注入真实对象；Desktop JCEF 无此机制，
 * 改用 CefMessageRouter（cefQuery）桥接：垫片把调用转为 window.cefQuery({request:'...'})，
 * Kotlin 侧 CefMessageRouterHandlerAdapter.onQuery 据此更新状态。
 *
 * 注入时机：主帧 onLoadStart（当前 jcefgithub fork 未暴露 onContextCreated，
 * onLoadStart 是可用的最早时机，早于页面脚本执行与 window load 事件）注入，
 * 主帧 onLoadEnd 再幂等兜底注入一次。
 * 尽早注入的原因：页面 window load 阶段会同步调用 bridgeError
 * （WebGL 不支持 / pannellum 缺失），load 之后才注入会丢失这些错误，
 * 退化为 25s 超时误报。
 *
 * 幂等：垫片开头检查 window.AndroidBridge 是否已存在，已存在则跳过。
 */
private val ANDROID_BRIDGE_SHIM_JS = """
(function(){
  if(window.AndroidBridge) return;
  window.AndroidBridge = {
    log: function(m) { try { window.cefQuery({request:'log:' + m}); } catch(e) { console.log(m); } },
    onPanoramaLoaded: function() { try { window.cefQuery({request:'loaded'}); } catch(e) { console.log('loaded'); } },
    onPanoramaError: function(m) { try { window.cefQuery({request:'error:' + m}); } catch(e) { console.error(m); } }
  };
})();
""".trimIndent()

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
        // file:// 页面加载同目录图片：Pannellum 通过 XHR 把全景图拉取为 Blob、
        // 再生成 blob: URL 交给 Image 解码后上传 WebGL 纹理（blob 源纹理天然不污染）。
        // 真正的限制在于 Chromium 默认禁止 file:// 页面发起 XHR（file:// 被视为
        // 不透明源，XHR 直接被拦截 → 图片取不到 → 黑屏/loadError），因此需放开限制。
        // jcefgithub 官方注入命令行开关的方式是 addJcefArgs（args 经
        // CefApp.getInstance(args, settings) 传入，由 MavenCefAppHandlerAdapter
        // 委托 CefAppHandlerAdapter.onBeforeCommandLineProcessing 解析为
        // Chromium switches；MavenCefAppHandlerAdapter.onBeforeCommandLineProcessing
        // 是 final 的，不能 override 注入）。
        builder.addJcefArgs(
            "--allow-file-access-from-files",
            "--allow-universal-access-from-files",
        )
        // macOS 兼容性：必须通过 setAppHandler 设置（不能直接调 CefApp.addAppHandler）
        builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})
        // build() 线程安全且幂等：安装原生库 → 初始化 CefApp → 注册 shutdown hook
        builder.build()
        jcefInitialized.set(true)
    }
}

/**
 * 准备全景图文件目录，与 Android 端策略一致（共享模板 + 图片原文件 + 相对路径配置）：
 *
 *   panorama_<ts>/
 *     ├── index.html        共享模板 panorama_viewer.html 原样写入
 *     ├── pannellum.js      共享资源
 *     ├── pannellum.css     共享资源
 *     ├── panorama_data.js  仅含 window.__panoramaConfig 相对路径配置
 *     └── panorama.{png|jpg|webp}  全景图原文件（保留原始格式扩展名）
 *
 * 关键设计：
 * - 不再 base64 内联图片、不再 JS 层 atob → Uint8Array → blob URL 中间层
 *   （旧 JavaFX WebView 方案规避 file:// XHR 限制的产物，大全景图会瞬时放大
 *   4~5 倍内存导致 OOM/白屏；JCEF 基于 Chromium，放开 file:// 访问后即可
 *   直接加载同目录图片）。
 * - panorama_viewer.html / pannellum.js 均含 JS 代码（$a 等变量名），
 *   通过 Res.readBytes() 原样写盘，不经过 Kotlin 模板字符串，无 $ 转义问题。
 * - 模板内置 bridgeLog / bridgeLoaded / bridgeError：Desktop 无 AndroidBridge，
 *   自动降级为 console.log + 页面内 hint 提示；WebGL 探测、错误处理由模板负责。
 */
private suspend fun preparePanoramaFiles(imageUrl: String): File {
    // 1. 创建临时目录（统一父目录 {tmpdir}/lingxi-panorama/panorama_<ts>，
    //    JVM shutdown hook 在 PanoramaTempDir 内全局仅注册一次，退出时删除整个父目录）
    val dir = PanoramaTempDir.allocateDir()

    // 2. 写入 pannellum.js / pannellum.css（独立文件，不内联）
    File(dir, "pannellum.js").writeBytes(Res.readBytes("files/panorama/pannellum.js"))
    File(dir, "pannellum.css").writeBytes(Res.readBytes("files/panorama/pannellum.css"))

    // 3. 写入 index.html（共享模板 panorama_viewer.html 原样写盘，纯模板不含图片数据）
    File(dir, "index.html").writeBytes(Res.readBytes("files/panorama/panorama_viewer.html"))

    // 4. 全景图原样写入本地文件，扩展名如实反映内容格式（避免 MIME 与内容不匹配
    //    导致解码失败；旧代码无论格式一律声明 image/jpeg）
    val imageBytes = readImageBytes(imageUrl)
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

    // 5. 写入 panorama_data.js（仅写相对路径配置，文件名与上面写入的一致；
    //    模板入口读取 window.__panoramaConfig.url 后交给 Pannellum 同源加载）
    File(dir, "panorama_data.js").writeText(
        "window.__panoramaConfig = {url: \"$imageFileName\"};"
    )

    // 临时文件清理由 PanoramaTempDir 内全局唯一的 shutdown hook 兜底（退出时
    // 递归删除整个 lingxi-panorama 父目录），无需再对单个文件调用 deleteOnExit()。
    return File(dir, "index.html")
}

/**
 * 读取图片字节。
 */
private suspend fun readImageBytes(imageUrl: String): ByteArray {
    return withContext(Dispatchers.IO) {
        when {
            imageUrl.startsWith("data:") -> {
                // 按首个逗号切分头部与数据（容忍无 base64 标记的 data URL）；
                // MimeDecoder 容忍数据中的空白/换行
                val base64Data = imageUrl.substringAfter(",", "")
                if (base64Data.isEmpty()) {
                    throw IllegalArgumentException("非法 data URL（缺少数据部分）")
                }
                Base64.getMimeDecoder().decode(base64Data)
            }
            imageUrl.startsWith("file://") -> {
                resolveFileUrl(imageUrl).readBytes()
            }
            imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                downloadWithRedirects(imageUrl)
            }
            else -> {
                try {
                    File(imageUrl).readBytes()
                } catch (e: Exception) {
                    // 截断 URL 防止 data URL（多 MB base64）写入异常消息和日志
                    throw IllegalArgumentException("无法识别的图片URL格式: ${imageUrl.take(100)}", e)
                }
            }
        }
    }
}

/**
 * 下载 http(s) 图片，手动跟随重定向（含跨协议 http→https）。
 *
 * HttpURLConnection 默认 instanceFollowRedirects=true 时不跟随跨协议重定向
 * （http→https 的 301 会直接报 HTTP 301），且默认 UA（Java/xx）可能被 CDN 403，
 * 因此关闭自动重定向、伪装浏览器 UA 并递归处理 301/302/303/307/308。
 *
 * @param url 当前请求 URL
 * @param redirectsLeft 剩余允许的重定向次数（防环）
 */
private fun downloadWithRedirects(url: String, redirectsLeft: Int = 5): ByteArray {
    val conn = URI(url).toURL().openConnection() as java.net.HttpURLConnection
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 LingxiDesktop/1.0")
    conn.connectTimeout = 15000
    conn.readTimeout = 30000
    conn.useCaches = false
    try {
        return when (val code = conn.responseCode) {
            in 200..299 -> conn.inputStream.use { it.readBytes() }
            301, 302, 303, 307, 308 -> {
                if (redirectsLeft <= 0) {
                    throw java.io.IOException("重定向次数过多: $url")
                }
                val location = conn.getHeaderField("Location")
                    ?: throw java.io.IOException("HTTP $code 缺少 Location 头")
                // Location 可能是相对路径，按当前 URL 解析
                downloadWithRedirects(URI(url).resolve(location).toString(), redirectsLeft - 1)
            }
            else -> throw java.io.IOException("HTTP $code")
        }
    } finally {
        conn.disconnect()
    }
}

/**
 * 解析 file:// URL 为本地 [File]。
 *
 * 优先按 URI 规范解析（正确处理 file:///C:/... 三斜杠 Windows 形式，
 * 即 LocalMessageStore.saveImage 产生的本地缓存路径）；URI 解析失败时
 * 回退为前缀剥离（兼容 file://C:/... 两斜杠与纯路径）。
 * 直接 removePrefix("file://") 会把 file:///C:/x 解析为 /C:/x，在 Windows
 * 上被 File 当作「当前盘符:\C:\x」，必然找不到文件（与 W5 同类 bug）。
 */
private fun resolveFileUrl(url: String): File {
    runCatching { return File(URI(url)) }
    val path = url.removePrefix("file:///").removePrefix("file://")
    // Windows 下 /C:/... 形式需去掉前导斜杠
    return if (path.matches(Regex("^/[A-Za-z]:/.*"))) File(path.substring(1)) else File(path)
}

/**
 * 当前系统是否为 macOS。
 * macOS 上 JCEF 不支持 windowed 渲染（createBrowser 必失败），
 * 全景查看器在 macOS 上直接走系统浏览器降级路径。
 */
private fun isMacOs(): Boolean =
    System.getProperty("os.name").lowercase().contains("mac")

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
