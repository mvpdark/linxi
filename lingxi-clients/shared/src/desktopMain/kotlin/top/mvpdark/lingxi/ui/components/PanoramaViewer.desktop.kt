package top.mvpdark.lingxi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * Desktop 全景查看器：暂时降级为静态图片展示。
 *
 * Desktop 端的 WebView 支持需要 JCEF 或 JavaFX WebView，引入成本较高。
 * 当前先保证功能可用（静态展示全景图），后续可接入 JCEF 实现真 360 度交互。
 * Android 端通过 WebView + Pannellum 实现完整的球面投影 360 度浏览。
 */
@Composable
actual fun PanoramaViewer(
    imageUrl: String,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "全景图",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
