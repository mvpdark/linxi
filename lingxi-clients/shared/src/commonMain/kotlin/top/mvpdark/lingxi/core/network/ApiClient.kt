package top.mvpdark.lingxi.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import top.mvpdark.lingxi.core.util.UrlResolver

/**
 * 灵犀后端 API 客户端。
 *
 * 封装 Ktor HttpClient，统一管理：
 * - JSON 序列化（kotlinx.serialization）
 * - 鉴权（Bearer Token 注入，从 [TokenStore] 动态读取）
 * - WebSocket 支持
 * - 基础 URL
 *
 * @param tokenStore token 持久化存储
 * @param baseUrl 后端基地址，默认使用 [UrlResolver.BASE_URL]（生产环境）
 */
class ApiClient(
    val tokenStore: TokenStore,
    val baseUrl: String = UrlResolver.BASE_URL,
) {
    /** 共享的 JSON 配置：宽松解析，忽略未知字段。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** 鉴权拦截器插件：每个请求自动注入 Bearer Token。 */
    private val authPlugin = createClientPlugin("AuthPlugin") {
        val store = tokenStore
        onRequest { request, _ ->
            val token = store.getAccessToken()
            if (!token.isNullOrBlank() && request.headers[HttpHeaders.Authorization] == null) {
                request.header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
    }

    /** 已配置好的 HttpClient 实例。 */
    val httpClient: HttpClient = HttpClient(createEngine()) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        install(authPlugin)
        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
        }
    }

    /** WebSocket 完整地址。 */
    val wsBaseUrl: String = baseUrl
        .replace("http://", "ws://")
        .replace("https://", "wss://")

    /** 关闭客户端，释放资源。 */
    fun close() {
        httpClient.close()
    }
}
