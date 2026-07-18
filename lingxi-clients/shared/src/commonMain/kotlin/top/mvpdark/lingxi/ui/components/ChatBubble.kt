package top.mvpdark.lingxi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import top.mvpdark.lingxi.core.util.UrlResolver
import top.mvpdark.lingxi.ui.theme.AiBubble
import top.mvpdark.lingxi.ui.theme.LocalDarkTheme
import top.mvpdark.lingxi.ui.theme.userBubbleBrush

/**
 * 聊天气泡组件。
 *
 * - AI 气泡：奶白色背景，20dp 圆角，左对齐
 * - 用户气泡：柔和绿色渐变背景，右对齐
 * - 支持文本和图片内容
 * - 显示时间戳
 *
 * @param text 消息文本。
 * @param isUser 是否为用户消息（false 表示 AI 消息）。
 * @param images 图片 URL 列表（data URL 或 http(s) URL）。
 * @param timestamp 时间戳文本（已格式化）。
 * @param modifier 修饰符。
 */
@Composable
fun ChatBubble(
    text: String,
    isUser: Boolean,
    images: List<String> = emptyList(),
    timestamp: String = "",
    modifier: Modifier = Modifier,
) {
    val isDark = LocalDarkTheme.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        brush = if (isUser) {
                            userBubbleBrush(isDark)
                        } else {
                            SolidColor(if (isDark) Color(0xFF2E2E2C) else AiBubble)
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                        overflow = TextOverflow.Visible,
                    )
                }
            }

            // 图片内容
            if (images.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    images.forEach { imageUrl ->
                        AsyncImage(
                            model = UrlResolver.resolveImageUrl(imageUrl),
                            contentDescription = "消息图片",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .sizeIn(maxWidth = 240.dp, maxHeight = 320.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                    }
                }
            }

            // 时间戳
            if (timestamp.isNotBlank()) {
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp, start = 4.dp),
                )
            }
        }
    }
}
