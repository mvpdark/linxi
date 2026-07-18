package top.mvpdark.lingxi.core.network

import java.util.prefs.Preferences

/**
 * Desktop 平台 TokenStore：基于 java.util.prefs.Preferences 持久化。
 *
 * @param context Desktop 平台上下文（空占位，未使用）。
 */
actual class TokenStore(context: PlatformContext) {

    private val prefs: Preferences = Preferences.userRoot().node("top/mvpdark/lingxi")

    actual suspend fun getAccessToken(): String? {
        return prefs.get(KEY_ACCESS_TOKEN, null)
    }

    actual suspend fun setAccessToken(token: String?) {
        if (token.isNullOrBlank()) {
            prefs.remove(KEY_ACCESS_TOKEN)
        } else {
            prefs.put(KEY_ACCESS_TOKEN, token)
        }
    }

    actual suspend fun getRefreshToken(): String? {
        return prefs.get(KEY_REFRESH_TOKEN, null)
    }

    actual suspend fun setRefreshToken(token: String?) {
        if (token.isNullOrBlank()) {
            prefs.remove(KEY_REFRESH_TOKEN)
        } else {
            prefs.put(KEY_REFRESH_TOKEN, token)
        }
    }

    actual suspend fun clear() {
        prefs.remove(KEY_ACCESS_TOKEN)
        prefs.remove(KEY_REFRESH_TOKEN)
    }

    private companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
