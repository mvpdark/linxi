package top.mvpdark.lingxi.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lingxi.core.util.formatMessageTime
import top.mvpdark.lingxi.core.util.formatSessionTime
import top.mvpdark.lingxi.data.model.ChatMessage
import top.mvpdark.lingxi.ui.chat.ChatViewModel
import top.mvpdark.lingxi.ui.components.ChatBubble
import top.mvpdark.lingxi.ui.emoji.AnimatedEmoji
import top.mvpdark.lingxi.ui.emoji.EmojiState

/**
 * 聊天页面（核心页面）。
 *
 * - 会话列表侧边栏（可展开/收起）
 * - 消息列表（LazyColumn）：AI 气泡（左）+ 用户气泡（右）
 * - Agent 状态卡片（显示 routing/dispatch/agent_done 等）
 * - 底部输入栏：文本输入框 + 发送按钮 + 图片上传按钮（暂用图标占位）
 * - 流式消息实时显示（delta 追加）
 *
 * @param sessionId 当前会话 ID（由导航参数传入）。
 * @param chatViewModel 聊天 ViewModel（Koin 注入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    chatViewModel: ChatViewModel = koinViewModel(),
) {
    val state by chatViewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // 进入时选中会话并加载历史消息
    LaunchedEffect(sessionId) {
        chatViewModel.selectSession(sessionId)
    }

    // 消息列表变化时滚动到底部
    LaunchedEffect(state.messages.size, state.streamingText) {
        val lastIndex = state.messages.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (lastIndex > 0) {
            listState.animateScrollToItem(lastIndex - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "团团",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (state.isSending) "正在回复..." else "猫娘 AI 助手",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = chatViewModel::toggleSessionsPanel) {
                        Icon(Icons.Default.Menu, contentDescription = "会话列表")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 会话列表侧边栏
                AnimatedVisibility(visible = state.isSessionsPanelOpen) {
                    SessionSidebar(
                        sessions = state.sessions,
                        currentSessionId = state.currentSessionId,
                        onSelect = chatViewModel::selectSession,
                        onCreateNew = {
                            chatViewModel.createNewSession { id -> }
                        },
                        onTogglePin = chatViewModel::togglePin,
                        onDelete = chatViewModel::deleteSession,
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 260.dp)
                            .width(260.dp),
                    )
                }

                // 主聊天区
                Column(modifier = Modifier.fillMaxSize()) {
                    // 消息列表
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.messages.isEmpty() && state.streamingText.isEmpty()) {
                            item {
                                EmptyChatHint()
                            }
                        }

                        // 历史与用户消息
                        items(state.messages, key = { it.id }) { message ->
                            ChatBubble(
                                text = message.content,
                                isUser = message.role == "user",
                                images = message.images,
                                timestamp = formatMessageTime(message.timestamp),
                            )
                        }

                        // Agent 状态卡片（团团表情 + 状态文字）
                        if (state.agentStatus != null && state.streamingText.isEmpty()) {
                            item {
                                AgentStatusCard(
                                    status = state.agentStatus!!,
                                    events = state.agentEvents,
                                    emojiState = state.emojiState,
                                )
                            }
                        }

                        // 流式回复气泡（实时追加 + 团团表情）
                        if (state.streamingText.isNotEmpty()) {
                            item {
                                Column {
                                    ChatBubble(
                                        text = state.streamingText,
                                        isUser = false,
                                    )
                                    if (state.isSending) {
                                        TypingIndicator()
                                    }
                                }
                            }
                        }

                        // 发送中但还没开始流式回复：显示团团表情
                        if (state.isSending && state.streamingText.isEmpty() && state.agentStatus == null) {
                            item {
                                EmojiDisplayRow(emojiState = state.emojiState)
                            }
                        }
                    }

                    HorizontalDivider()

                    // 底部输入栏
                    ChatInputBar(
                        text = state.inputText,
                        onTextChange = chatViewModel::onInputTextChange,
                        onSend = { chatViewModel.sendMessage() },
                        isSending = state.isSending,
                    )
                }
            }
        }
    }
}

/**
 * 会话侧边栏。
 *
 * 特性：
 * - 会话项支持双向滑动：左滑固定/取消固定，右滑删除
 * - 置顶会话显示图钉图标，排在列表顶部
 * - 长按会话项弹出删除确认对话框
 */
@Composable
private fun SessionSidebar(
    sessions: List<top.mvpdark.lingxi.data.model.ChatSession>,
    currentSessionId: String,
    onSelect: (String) -> Unit,
    onCreateNew: () -> Unit,
    onTogglePin: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 删除确认对话框状态
    var deleteTarget by remember { mutableStateOf<top.mvpdark.lingxi.data.model.ChatSession?>(null) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "会话列表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, contentDescription = "新建会话")
                }
            }
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SwipeableSessionItem(
                        session = session,
                        isSelected = session.id == currentSessionId,
                        onClick = { onSelect(session.id) },
                        onTogglePin = { onTogglePin(session.id) },
                        onDelete = { deleteTarget = session },
                    )
                }
            }
        }
    }

    // 删除确认对话框
    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确定要删除「${session.title}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 可滑动的会话项。
 *
 * 交互：
 * - 左滑（向左拖动）：露出右侧"固定/取消固定"按钮
 * - 右滑（向右拖动）：露出左侧"删除"按钮
 * - 点击会话项主体：切换会话
 * - 释放时根据偏移量决定操作或回弹
 */
@Composable
private fun SwipeableSessionItem(
    session: top.mvpdark.lingxi.data.model.ChatSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val maxSwipe = 80.dp
    val maxSwipePx = with(androidx.compose.ui.platform.LocalDensity.current) { maxSwipe.toPx() }
    val threshold = maxSwipePx * 0.5f

    var offsetX by remember(session.id) { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(56.dp),
    ) {
        // 背景操作按钮层
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：删除按钮（右滑时露出）
            if (offsetX > 0) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }

            // 右侧：固定/取消固定按钮（左滑时露出）
            if (offsetX < 0) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onTogglePin() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = if (session.pinned) "取消固定" else "固定",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // 前景：会话项主体
        Row(
            modifier = Modifier
                .fillMaxSize()
                .absoluteOffset { IntOffset(offsetX.roundToInt(), 0) }
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    }
                )
                .clickable {
                    // 如果处于滑动状态，先复位
                    if (offsetX != 0f) {
                        offsetX = 0f
                    } else {
                        onClick()
                    }
                }
                .pointerInput(session.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            // 释放时根据偏移量决定操作
                            when {
                                offsetX <= -threshold -> {
                                    // 左滑超过阈值：触发固定
                                    onTogglePin()
                                    offsetX = 0f
                                }
                                offsetX >= threshold -> {
                                    // 右滑超过阈值：触发删除
                                    onDelete()
                                    offsetX = 0f
                                }
                                else -> {
                                    // 未超过阈值：回弹
                                    offsetX = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            offsetX = 0f
                        },
                    ) { _, dragAmount ->
                        // 累加偏移量，限制在 [-maxSwipePx, maxSwipePx]
                        offsetX = (offsetX + dragAmount).coerceIn(-maxSwipePx, maxSwipePx)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 置顶图标（已固定时显示）
            if (session.pinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "已固定",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
                if (session.updatedAt.isNotBlank()) {
                    Text(
                        text = formatSessionTime(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Agent 状态卡片：显示团团 APNG 表情 + routing/dispatch/agent_done 等执行过程。
 */
@Composable
private fun AgentStatusCard(
    status: String,
    events: List<top.mvpdark.lingxi.data.model.AgentEvent>,
    emojiState: EmojiState = EmojiState.THINKING,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 团团 APNG 动画表情
            AnimatedEmoji(
                resourcePath = emojiState.resourcePath,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (events.isNotEmpty()) {
                    Text(
                        text = "Agent: ${events.joinToString { it.agentName }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 表情显示行（无状态文字时仅显示团团表情）。
 */
@Composable
private fun EmojiDisplayRow(
    emojiState: EmojiState = EmojiState.IDLE,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedEmoji(
            resourcePath = emojiState.resourcePath,
            size = 48.dp,
        )
    }
}

/**
 * 流式打字指示器。
 */
@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
        )
    }
}

/**
 * 空会话提示（团团 idle 表情 + 欢迎语）。
 */
@Composable
private fun EmptyChatHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 团团 idle 动画表情
        AnimatedEmoji(
            resourcePath = EmojiState.IDLE.resourcePath,
            size = 80.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "喵～团团在这里！\n有什么设计灵感想聊聊吗？",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 底部输入栏。
 *
 * @param text 输入文本。
 * @param onTextChange 文本变化回调。
 * @param onSend 发送回调。
 * @param isSending 是否正在发送（禁用按钮 + 显示 loading）。
 */
@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图片上传按钮（暂用图标占位）
        IconButton(onClick = { /* TODO: 接入图片选择器 */ }) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = "上传图片",
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            placeholder = { Text("给团团说点什么...") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            trailingIcon = {
                IconButton(
                    onClick = onSend,
                    enabled = !isSending && text.isNotBlank(),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
        )
    }
}
