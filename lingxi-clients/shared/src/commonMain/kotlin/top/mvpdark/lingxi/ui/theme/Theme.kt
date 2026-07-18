package top.mvpdark.lingxi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 应用主题。
 *
 * @param darkTheme 是否使用深色模式，默认跟随系统。
 * @param content 主题包裹的内容。
 */
@Composable
fun LingxiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        LingxiDarkColorScheme
    } else {
        LingxiLightColorScheme
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LingxiTypography,
            content = content,
        )
    }
}

/** 当前是否深色模式的全局可读值，供气泡等组件取色用。 */
val LocalDarkTheme = staticCompositionLocalOf { false }
