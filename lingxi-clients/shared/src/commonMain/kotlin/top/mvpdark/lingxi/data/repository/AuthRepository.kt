package top.mvpdark.lingxi.data.repository

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.network.TokenStore
import top.mvpdark.lingxi.data.model.LoginRequest
import top.mvpdark.lingxi.data.model.LoginResponse
import top.mvpdark.lingxi.data.model.MeResponse
import top.mvpdark.lingxi.data.model.RegisterRequest

/**
 * 认证仓库：封装 /api/auth/* 接口。
 */
class AuthRepository(
    private val apiClient: ApiClient,
    private val tokenStore: TokenStore,
) {
    /** 登录，成功后持久化 token。失败抛出 [AuthException]。 */
    suspend fun login(username: String, password: String): LoginResponse {
        val response = apiClient.httpClient.post("/api/auth/login") {
            setBody(LoginRequest(username, password))
        }
        val body: LoginResponse = response.body()
        if (!body.ok) {
            throw AuthException(body.error.ifBlank { "用户名或密码错误" })
        }
        // 持久化 token
        tokenStore.setAccessToken(body.accessToken)
        tokenStore.setRefreshToken(body.refreshToken)
        return body
    }

    /** 注册，成功后返回 true。 */
    suspend fun register(username: String, password: String): Boolean {
        val response = apiClient.httpClient.post("/api/auth/register") {
            setBody(RegisterRequest(username, password))
        }
        if (response.status != HttpStatusCode.OK) {
            val body: LoginResponse = response.body()
            throw AuthException(body.error.ifBlank { "注册失败" })
        }
        return true
    }

    /** 获取当前用户信息（/api/auth/me）。 */
    suspend fun getMe(): MeResponse {
        return apiClient.httpClient.get("/api/auth/me").body()
    }

    /** 退出登录：通知后端 + 清除本地 token。 */
    suspend fun logout() {
        runCatching {
            apiClient.httpClient.post("/api/auth/logout")
        }
        tokenStore.clear()
    }

    /** 当前是否已登录（本地存在 access token）。 */
    suspend fun isLoggedIn(): Boolean {
        return !tokenStore.getAccessToken().isNullOrBlank()
    }
}

/** 认证相关异常。 */
class AuthException(message: String) : Exception(message)
