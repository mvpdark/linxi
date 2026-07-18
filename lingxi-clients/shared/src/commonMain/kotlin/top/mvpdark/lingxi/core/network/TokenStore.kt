package top.mvpdark.lingxi.core.network

/**
 * Token 持久化存储（跨平台抽象）。
 *
 * - Android: 基于 DataStore Preferences
 * - Desktop: 基于 java.util.prefs.Preferences
 *
 * 构造需要平台上下文，由各平台 [PlatformModule] 注入。
 */
expect class TokenStore(context: PlatformContext) {

    /** 读取 access token，无则返回 null。 */
    suspend fun getAccessToken(): String?

    /** 写入 access token，传入 null 表示清除。 */
    suspend fun setAccessToken(token: String?)

    /** 读取 refresh token，无则返回 null。 */
    suspend fun getRefreshToken(): String?

    /** 写入 refresh token，传入 null 表示清除。 */
    suspend fun setRefreshToken(token: String?)

    /** 清除全部 token（退出登录时调用）。 */
    suspend fun clear()
}
