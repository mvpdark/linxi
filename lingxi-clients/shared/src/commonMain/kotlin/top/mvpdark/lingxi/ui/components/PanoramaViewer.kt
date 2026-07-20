package top.mvpdark.lingxi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全景图查看器：基于 Pannellum 的真 360 度球面投影查看器。
 *
 * - Android：WebView 加载自包含 HTML —— 全景图转 Base64 后以 data: URL
 *   连同 pannellum.js/css 一并内联，loadDataWithBaseURL 一次加载，
 *   加载后零网络/文件请求，彻底规避路径映射与同源策略问题。
 * - Desktop：内嵌 JCEF / Chromium（SwingPanel + CefBrowser）直接渲染 Pannellum；
 *   将 Pannellum + 全景图写入临时目录后以 file:/// URL 加载
 *   （需 --allow-file-access-from-files，Pannellum 经 XHR 取图）；
 *   JCEF 不可用（原生库缺失 / macOS 不支持窗口渲染）时降级为系统默认浏览器打开。
 *
 * Pannellum 资源打包在 KMP resources（composeResources/files/panorama/）。
 *
 * @param imageUrl 全景图地址：当前后端契约恒为 `data:image/png;base64,...`，
 *   本地持久化后为 `file://` 路径；亦兼容 http(s) URL 与裸路径
 * @param modifier 修饰符
 */
@Composable
expect fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
)
