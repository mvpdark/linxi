package top.mvpdark.lingxi.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.mvpdark.lingxi.data.model.AgentEvent
import top.mvpdark.lingxi.data.model.ChatMessage
import top.mvpdark.lingxi.data.model.ChatSession
import top.mvpdark.lingxi.core.util.currentTimeMillis
import top.mvpdark.lingxi.data.repository.ChatRepository

/**
 * 聊天 UI 状态。
 */
data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val agentStatus: String? = null,
    val agentEvents: List<AgentEvent> = emptyList(),
    val streamingText: String = "",
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isSessionsPanelOpen: Boolean = false,
    val error: String? = null,
)

/**
 * 聊天 ViewModel：管理会话列表、历史消息、流式收发。
 *
 * 通过 Koin 注入 [ChatRepository]。支持团团（猫娘 AI 助手）流式回复实时追加。
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 初始化：加载会话列表。 */
    init {
        loadSessions()
    }

    /** 加载会话列表。 */
    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { chatRepository.getSessions() }
                .onSuccess { list ->
                    _uiState.update {
                        it.copy(sessions = list, isLoading = false)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "加载会话失败: ${e.message}")
                    }
                }
        }
    }

    /** 创建新会话，并切换到聊天页。返回新会话 ID。 */
    fun createNewSession(
        title: String = "新对话",
        onCreated: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { chatRepository.createSession(title) }
                .onSuccess { session ->
                    _uiState.update {
                        it.copy(
                            sessions = listOf(session) + it.sessions,
                            isLoading = false,
                        )
                    }
                    onCreated(session.id)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "创建会话失败: ${e.message}")
                    }
                }
        }
    }

    /** 选择会话并加载历史消息。 */
    fun selectSession(sessionId: String) {
        if (_uiState.value.currentSessionId == sessionId) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    messages = emptyList(),
                    streamingText = "",
                    agentStatus = null,
                    agentEvents = emptyList(),
                    isSessionsPanelOpen = false,
                    error = null,
                )
            }
            runCatching { chatRepository.getHistory(sessionId) }
                .onSuccess { history ->
                    _uiState.update { it.copy(messages = history) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "加载历史失败: ${e.message}") }
                }
        }
    }

    /** 更新输入框文本。 */
    fun onInputTextChange(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    /** 切换侧边栏展开/收起。 */
    fun toggleSessionsPanel() {
        _uiState.update { it.copy(isSessionsPanelOpen = !it.isSessionsPanelOpen) }
    }

    /** 发送消息并接收流式回复。 */
    fun sendMessage(
        imageUrl: String = "",
    ) {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty() && imageUrl.isEmpty()) return
        if (state.isSending) return

        // 立即清空输入并标记发送中
        _uiState.update {
            it.copy(
                inputText = "",
                isSending = true,
                streamingText = "",
                agentStatus = "团团思考中...",
                agentEvents = emptyList(),
                error = null,
            )
        }

        val pendingText = text
        val pendingImage = imageUrl

        viewModelScope.launch {
            // 确保有会话（异步创建，避免阻塞主线程）
            val sessionId = _uiState.value.currentSessionId.ifBlank {
                runCatching { chatRepository.createSession("新对话") }
                    .onSuccess { session ->
                        _uiState.update {
                            it.copy(sessions = listOf(session) + it.sessions)
                        }
                    }
                    .getOrNull()?.id
            }
            if (sessionId.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        agentStatus = null,
                        error = "没有可用会话，请稍后重试",
                    )
                }
                return@launch
            }

            // 追加用户消息到列表
            val userMessage = ChatMessage(
                id = "local_${currentTimeMillis()}",
                sessionId = sessionId,
                role = "user",
                content = pendingText,
                images = if (pendingImage.isNotEmpty()) listOf(pendingImage) else emptyList(),
                timestamp = "",
            )
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    messages = it.messages + userMessage,
                )
            }

            // 流式接收 AgentEvent
            chatRepository.sendMessageStream(sessionId, pendingText, pendingImage)
                .collect { event ->
                    handleAgentEvent(sessionId, event)
                }
            _uiState.update { it.copy(isSending = false, agentStatus = null) }
        }
    }

    /** 处理单个 AgentEvent，更新 UI 状态。 */
    private fun handleAgentEvent(sessionId: String, event: AgentEvent) {
        when (event.type) {
            "auth_ok" -> Unit
            "routing" -> {
                _uiState.update { it.copy(agentStatus = "团团正在理解你的需求...") }
            }
            "dispatch" -> {
                val agents = event.agentsDispatched.joinToString("、").ifBlank { "团团" }
                _uiState.update {
                    it.copy(agentStatus = "团团派出了：$agents")
                }
            }
            "status" -> {
                if (event.content.isNotBlank()) {
                    _uiState.update { it.copy(agentStatus = event.content) }
                }
            }
            "synthesis_start" -> {
                _uiState.update { it.copy(agentStatus = "团团整理回答中...") }
            }
            "agent_done" -> {
                val msg = "${event.agentName}完成"
                _uiState.update {
                    it.copy(
                        agentStatus = msg,
                        agentEvents = it.agentEvents + event,
                    )
                }
            }
            "agent_error" -> {
                _uiState.update {
                    it.copy(
                        agentStatus = "${event.agentName}出错",
                        agentEvents = it.agentEvents + event,
                    )
                }
            }
            "delta" -> {
                // 流式增量追加
                _uiState.update {
                    it.copy(streamingText = it.streamingText + event.content)
                }
            }
            "search_image" -> {
                _uiState.update {
                    it.copy(agentEvents = it.agentEvents + event)
                }
            }
            "done" -> {
                // 流结束：把累积的文本固化成一条 assistant 消息
                val fullText = _uiState.value.streamingText
                if (fullText.isNotEmpty()) {
                    val aiMessage = ChatMessage(
                        id = "ai_${currentTimeMillis()}",
                        sessionId = sessionId,
                        role = "assistant",
                        content = fullText,
                        timestamp = "",
                    )
                    _uiState.update {
                        it.copy(
                            messages = it.messages + aiMessage,
                            streamingText = "",
                            agentStatus = null,
                        )
                    }
                }
            }
            "error" -> {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        agentStatus = null,
                        error = event.error.ifBlank { event.content.ifBlank { "团团开小差了" } },
                    )
                }
            }
            else -> Unit
        }
    }

    /** 清除错误提示。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 删除会话。 */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            runCatching { chatRepository.deleteSession(sessionId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(sessions = it.sessions.filterNot { s -> s.id == sessionId })
                    }
                }
        }
    }
}
