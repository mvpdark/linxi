package top.mvpdark.lingxi.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.koin.core.context.startKoin
import top.mvpdark.lingxi.LingxiApp
import top.mvpdark.lingxi.di.appModule
import top.mvpdark.lingxi.di.platformModule

/**
 * Desktop 应用入口。
 *
 * - 启动 Koin（appModule + platformModule）
 * - 创建 1280×800 窗口，标题「灵犀」
 * - 渲染 [LingxiApp] 共享根组件
 */
fun main() {
    // 1. 启动 Koin（必须在渲染 Composable 前完成）
    startKoin {
        modules(appModule, platformModule)
    }

    // 2. 创建应用窗口
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "灵犀",
            width = 1280.dp,
            height = 800.dp,
        ) {
            LingxiApp()
        }
    }
}
