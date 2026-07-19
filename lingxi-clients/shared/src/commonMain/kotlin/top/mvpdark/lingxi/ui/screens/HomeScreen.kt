package top.mvpdark.lingxi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lingxi.core.util.formatDouble
import top.mvpdark.lingxi.core.util.formatSessionTime
import top.mvpdark.lingxi.ui.auth.AuthViewModel
import top.mvpdark.lingxi.ui.chat.ChatViewModel

/**
 * 首页。
 *
 * 设计哲学："Quiet Materiality"（沉静的物性）
 * - 暖奶油色背景承载鼠尾草绿强调色
 * - 2×2 功能卡片网格：深绿图标背景 + 白色图标
 * - 最近会话列表：浅灰卡片 + 友好时间格式
 *
 * - 顶部导航栏：用户名 + 余额 + 退出按钮（融入背景，无分隔线）
 * - 功能卡片网格：灵感(→chat) / 改图(→image-edit) / 全景(→panorama) / 更多
 * - 最近会话列表（前5条）
 *
 * @param authViewModel 认证 ViewModel（Koin 注入）。
 * @param chatViewModel 聊天 ViewModel（Koin 注入）。
 * @param onNavigateChat 导航到聊天页（sessionId 由新建/选择会话决定）。
 * @param onNavigateImageEdit 导航到改图页。
 * @param onNavigatePanorama 导航到全景页。
 * @param onLoggedOut 退出登录后的回调（回到登录页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    authViewModel: AuthViewModel = koinViewModel(),
    chatViewModel: ChatViewModel = koinViewModel(),
    onNavigateChat: (String) -> Unit = {},
    onNavigateImageEdit: () -> Unit = {},
    onNavigatePanorama: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
) {
    val authState by authViewModel.uiState.collectAsState()
    val chatState by chatViewModel.uiState.collectAsState()

    // 进入首页刷新会话列表与用户信息
    LaunchedEffect(Unit) {
        chatViewModel.loadSessions()
        authViewModel.refreshUser()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = authState.user?.username ?: "灵犀",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val balance = authState.user?.balance ?: 0.0
                        Text(
                            text = "余额 ¥${formatDouble(balance)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        authViewModel.logout()
                        onLoggedOut()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "退出登录",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 功能卡片网格
            item {
                Text(
                    text = "功能",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(260.dp),
                ) {
                    item {
                        FeatureCard(
                            title = "灵感",
                            subtitle = "与团团对话，激发设计灵感",
                            icon = Icons.AutoMirrored.Filled.Chat,
                            onClick = {
                                chatViewModel.createNewSession { id ->
                                    onNavigateChat(id)
                                }
                            },
                        )
                    }
                    item {
                        FeatureCard(
                            title = "改图",
                            subtitle = "草图一键生成专业效果图",
                            icon = Icons.Default.Image,
                            onClick = onNavigateImageEdit,
                        )
                    }
                    item {
                        FeatureCard(
                            title = "全景",
                            subtitle = "360 度全景图生成",
                            icon = Icons.Default.Panorama,
                            onClick = onNavigatePanorama,
                        )
                    }
                    item {
                        FeatureCard(
                            title = "更多",
                            subtitle = "敬请期待",
                            icon = Icons.Default.AutoAwesome,
                            onClick = {},
                            enabled = false,
                        )
                    }
                }
            }

            // 最近会话
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "最近会话",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            val recentSessions = chatState.sessions.take(5)
            if (recentSessions.isEmpty()) {
                item {
                    Text(
                        text = "还没有会话，点击「灵感」开始第一次对话吧～",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(recentSessions) { session ->
                    SessionRowItem(
                        title = session.title,
                        subtitle = formatSessionTime(session.updatedAt),
                        onClick = { onNavigateChat(session.id) },
                    )
                }
            }
        }
    }
}

/**
 * 功能卡片。
 *
 * 设计规范：
 * - primaryContainer 背景（浅薄荷绿 #D4E5D4）
 * - 20dp 大圆角
 * - 柔和阴影（1dp）
 * - 图标：深绿色（primary）圆角方形背景 + 白色图标
 * - 禁用态：降低透明度
 *
 * @param enabled 是否启用。false 时降低视觉权重（用于"更多"等未上线功能）。
 */
@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        modifier = Modifier
            .height(120.dp)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (enabled) 1.dp else 0.dp,
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.5f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 图标：深绿色圆角方形背景 + 白色图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Color.White,
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * 会话列表项。
 *
 * 设计规范：
 * - surfaceVariant 背景（浅米灰）
 * - 14dp 圆角
 * - 标题 + 友好时间格式
 */
@Composable
private fun SessionRowItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
