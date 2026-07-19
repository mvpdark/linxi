package top.mvpdark.lingxi

import androidx.compose.runtime.Composable
import org.koin.compose.koinInject
import top.mvpdark.lingxi.core.util.getAppVersion
import top.mvpdark.lingxi.core.util.isAutoUpdateSupported
import top.mvpdark.lingxi.data.repository.AuthRepository
import top.mvpdark.lingxi.ui.navigation.NavGraph
import top.mvpdark.lingxi.ui.theme.LingxiTheme
import top.mvpdark.lingxi.ui.update.UpdateCheckHost

/**
 * 应用根组件。
 *
 * - 用 [LingxiTheme] 包裹
 * - Koin 初始化检查（确保 Koin 已启动）
 * - 渲染 [NavGraph]
 * - Android 平台：启动时自动检查 GitHub Releases 新版本
 *
 * 注：启动时的登录态检查由 [top.mvpdark.lingxi.ui.auth.AuthViewModel] 的
 * checkInitialAuth() 在 init 中完成，此处无需重复调用。
 */
@Composable
fun LingxiApp() {
    LingxiTheme {
        // 触发 Koin 依赖解析，确认上下文可用
        // 确保平台入口（LingxiApplication/Main.kt）已先调用 startKoin，否则此处会抛 Koin 异常
        @Suppress("unused")
        val authRepository: AuthRepository = koinInject()

        NavGraph()

        // 自动更新检查（仅 Android 平台）
        if (isAutoUpdateSupported()) {
            UpdateCheckHost(currentVersion = getAppVersion())
        }
    }
}
