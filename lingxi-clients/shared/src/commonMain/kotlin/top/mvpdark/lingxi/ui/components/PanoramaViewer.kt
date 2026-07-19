package top.mvpdark.lingxi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全景图查看器：基于 Pannellum 的真 360 度球面投影查看器。
 *
 * - Android：用 [android.webkit.WebView] 加载本地 HTML（内嵌 Pannellum 2.5.6），
 *   全景图转 base64 → blob: URL 注入 Pannellum，避免 file:// XHR blob 返回 null。
 * - Desktop：将 Pannellum + 全景图写入临时目录，用系统默认浏览器打开（浏览器支持完整 WebGL）。
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
