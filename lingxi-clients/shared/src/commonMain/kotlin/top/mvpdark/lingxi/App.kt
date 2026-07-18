package top.mvpdark.lingxi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.koin.compose.koinInject
import top.mvpdark.lingxi.data.repository.AuthRepository
import top.mvpdark.lingxi.ui.navigation.NavGraph
import top.mvpdark.lingxi.ui.theme.LingxiTheme

/**
 * 应用根组件。
 *
 * - 用 [LingxiTheme] 包裹
 * - Koin 初始化检查（确保 Koin 已启动）
 * - 渲染 [NavGraph]
 */
@Composable
fun LingxiApp() {
    LingxiTheme {
        // 触发 Koin 依赖解析，确认上下文可用
        val authRepository: AuthRepository = koinInject()
        LaunchedEffect(Unit) {
            // 启动时确保仓库可访问（验证 Koin 已正确初始化）
            runCatching { authRepository.isLoggedIn() }
        }

        NavGraph()
    }
}
