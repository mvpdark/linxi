package top.mvpdark.lingxi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全景图查看器：基于 Pannellum 的真 360 度球面投影查看器。
 *
 * - Android：用 [android.webkit.WebView] 加载本地 HTML（内嵌 Pannellum 2.5.6），
 *   通过 `window.__loadPanorama(url)` JS 桥接注入全景图 URL。
 * - Desktop：暂时降级为静态图片展示（Desktop 后续可接 JCEF）。
 *
 * Pannellum 资源打包在 KMP resources（composeResources/files/panorama/）。
 *
 * @param imageUrl 全景图 URL（data URL 或 http URL）
 * @param modifier 修饰符
 */
@Composable
expect fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
)
