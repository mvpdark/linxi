package top.mvpdark.lingxi.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 灵犀品牌色彩系统。
 *
 * 设计哲学："Quiet Materiality"（沉静的物性）——暖奶油色背景承载鼠尾草绿强调色，
 * AI 气泡奶白、用户气泡柔和绿色渐变，营造设计师工作台般的温润质感。
 */

// ---- 品牌基础色 ----

/** 暖奶油色背景。 */
val CreamBackground = Color(0xFFF8F6F3)

/** 鼠尾草绿强调色。 */
val SageGreen = Color(0xFF7A9B7A)

/** 深鼠尾草绿（用于文字/图标）。 */
val SageGreenDeep = Color(0xFF5C7A5C)

/** AI 气泡奶白色。 */
val AiBubble = Color(0xFFFFFFFF)

/** 用户气泡渐变起点（柔和绿）。 */
val UserBubbleStart = Color(0xFF8FB98F)

/** 用户气泡渐变终点（深一阶柔和绿）。 */
val UserBubbleEnd = Color(0xFF7DA87D)

/** 米色分割线。 */
val BeigeDivider = Color(0xFFE8E2D8)

// ---- 浅色模式扩展色 ----

val LightOnSurface = Color(0xFF2C2C2C)
val LightOnSurfaceVariant = Color(0xFF6B6B6B)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1EDE7)
val LightOutline = Color(0xFFC9C2B6)
val LightError = Color(0xFFB3261E)

// ---- 深色模式扩展色 ----

val DarkBackground = Color(0xFF1A1A18)
val DarkSurface = Color(0xFF262624)
val DarkSurfaceVariant = Color(0xFF34342F)
val DarkOnSurface = Color(0xFFE8E2D8)
val DarkOnSurfaceVariant = Color(0xFFB0AAA0)
val DarkOutline = Color(0xFF5A5750)
val DarkAiBubble = Color(0xFF2E2E2C)
val DarkUserBubbleStart = Color(0xFF5C7A5C)
val DarkUserBubbleEnd = Color(0xFF4A6849)
val DarkError = Color(0xFFF2B8B5)

/**
 * 浅色 Material3 配色方案。
 */
val LingxiLightColorScheme = lightColorScheme(
    primary = SageGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E5D4),
    onPrimaryContainer = Color(0xFF1F3A1F),
    secondary = SageGreenDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E3D8),
    onSecondaryContainer = Color(0xFF2A3A2A),
    tertiary = Color(0xFFA8896B),
    onTertiary = Color.White,
    background = CreamBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = BeigeDivider,
    error = LightError,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * 深色 Material3 配色方案。
 */
val LingxiDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C8A8),
    onPrimary = Color(0xFF1A2E1A),
    primaryContainer = Color(0xFF3A523A),
    onPrimaryContainer = Color(0xFFD4E5D4),
    secondary = Color(0xFFB5C9B5),
    onSecondary = Color(0xFF203020),
    secondaryContainer = Color(0xFF384838),
    onSecondaryContainer = Color(0xFFD8E3D8),
    tertiary = Color(0xFFC5A98C),
    onTertiary = Color(0xFF32271A),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF45433D),
    error = DarkError,
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * 用户气泡柔和绿色渐变画刷。
 * @param isDark 是否深色模式。
 */
fun userBubbleBrush(isDark: Boolean): Brush = if (isDark) {
    Brush.linearGradient(
        colors = listOf(DarkUserBubbleStart, DarkUserBubbleEnd),
    )
} else {
    Brush.linearGradient(
        colors = listOf(UserBubbleStart, UserBubbleEnd),
    )
}
