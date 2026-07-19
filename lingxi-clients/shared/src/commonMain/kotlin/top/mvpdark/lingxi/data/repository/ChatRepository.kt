package top.mvpdark.lingxi.data.repository

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.network.TokenStore
import top.mvpdark.lingxi.core.util.PlatformLogger
import top.mvpdark.lingxi.core.util.runCatchingCancellable
import top.mvpdark.lingxi.data.model.AgentEvent
import top.mvpdark.lingxi.data.model.AuthFrame
import top.mvpdark.lingxi.data.model.ChatFrame
import top.mvpdark.lingxi.data.model.ChatMessage
import top.mvpdark.lingxi.data.model.ChatSession
import top.mvpdark.lingxi.data.model.HistoryResponse
import top.mvpdark.lingxi.data.model.SessionListResponse

/**
 * 聊天仓库：封装 /api/sessions/ 与 WebSocket /ws/chat 接口。
 *
 * 流式消息通过 [sendMessageStream] 返回 [Flow]<[AgentEvent]>，UI 逐帧消费。
 */
class ChatRepository(
    private val apiClient: ApiClient,
    private val tokenStore: TokenStore,
) {
    /** 获取会话列表。后端返回 {sessions: [...]}。 */
    suspend fun getSessions(): List<ChatSession> {
        val response: SessionListResponse =
            apiClient.httpClient.get("/api/sessions").body()
        return response.sessions
    }

    /** 创建新会话。后端用 query 参数 title。 */
    suspend fun createSession(title: String = "新对话"): ChatSession {
        return apiClient.httpClient.post("/api/sessions") {
            parameter("title", title)
        }.body()
    }

    /** 删除会话。后端用 DELETE 方法。 */
    suspend fun deleteSession(sessionId: String) {
        apiClient.httpClient.delete("/api/sessions/$sessionId")
    }

    /** 加载历史消息。后端返回 {history: [...]}。 */
    suspend fun getHistory(sessionId: String): List<ChatMessage> {
        val response: HistoryResponse =
            apiClient.httpClient.get("/api/sessions/$sessionId/history").body()
        return response.history
    }

    /**
     * 通过 WebSocket 发送聊天消息，并流式接收 [AgentEvent]。
     *
     * 协议：
     * 1. 连接 /ws/chat
     * 2. 首帧发送 {"type":"auth","token":"..."}，等待 {"type":"auth_ok"}
     * 3. 发送 {"type":"chat","session_id":"...","message":"...","image_url":"..."}
     * 4. 逐帧接收 AgentEvent，直到 type == "done" 或连接关闭
     */
    fun sendMessageStream(
        sessionId: String,
        message: String,
        imageUrl: String = "",
    ): Flow<AgentEvent> = callbackFlow {
        val token = tokenStore.getAccessToken()
        if (token.isNullOrBlank()) {
            if (trySend(AgentEvent(type = "error", error = "未登录")).isFailure) {
                PlatformLogger.w("ChatRepository", "Failed to send event: channel may be closed")
            }
            close()
            return@callbackFlow
        }

        val wsUrl = "${apiClient.wsBaseUrl}/ws/chat"
        try {
            apiClient.httpClient.webSocket(
                method = HttpMethod.Get,
                request = {
                    url(wsUrl)
                },
            ) {
                // 1. 首帧鉴权
                // 注意：authPlugin 已在 WS 升级请求中注入 Authorization 头，
                // 此处额外发送 auth 帧是协议要求（服务端通过帧二次确认）
                val authFrame = apiClient.json.encodeToString(AuthFrame(token = token))
                send(Frame.Text(authFrame))

                // 等待服务端确认鉴权
                val authAck = incoming.receiveCatching().getOrNull() as? Frame.Text
                if (authAck != null) {
                    val ackText = authAck.readText()
                    if (!ackText.contains("auth_ok")) {
                        if (trySend(AgentEvent(type = "error", error = "鉴权失败：$ackText")).isFailure) {
                            PlatformLogger.w("ChatRepository", "Failed to send event: channel may be closed")
                        }
                        close()
                        return@webSocket
                    }
                }

                // 2. 发送聊天消息
                val chatFrame = apiClient.json.encodeToString(
                    ChatFrame(sessionId = sessionId, message = message, imageUrl = imageUrl)
                )
                send(Frame.Text(chatFrame))

                // 3. 接收流式事件
                for (frame in incoming) {
                    if (!isActive) break
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            val event = runCatchingCancellable {
                                apiClient.json.decodeFromString(AgentEvent.serializer(), text)
                            }.getOrNull() ?: continue

                            if (trySend(event).isFailure) {
                                PlatformLogger.w("ChatRepository", "Failed to send event: channel may be closed")
                            }

                            if (event.type == "done" || event.type == "error") {
                                break
                            }
                        }
                        is Frame.Binary -> Unit
                        is Frame.Close -> break
                        else -> Unit
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (trySend(AgentEvent(type = "error", error = e.message ?: "WebSocket 连接失败")).isFailure) {
                PlatformLogger.w("ChatRepository", "Failed to send event: channel may be closed")
            }
        } finally {
            close()
        }
        awaitClose { }
    }
}
