package top.mvpdark.lingxi.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 顶层 DataStore 扩展，按进程单例持有。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lingxi_prefs")

/**
 * Android 平台 TokenStore：基于 DataStore Preferences 持久化。
 *
 * @param context Android Context（[PlatformContext] 在 Android 上即 Context）。
 */
actual class TokenStore actual constructor(context: PlatformContext) {

    private val ctx: Context = context.androidContext
    private val dataStore: DataStore<Preferences> get() = ctx.dataStore

    actual suspend fun getAccessToken(): String? {
        return dataStore.data.map { it[KEY_ACCESS_TOKEN] }.first()
    }

    actual suspend fun setAccessToken(token: String?) {
        dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(KEY_ACCESS_TOKEN)
            } else {
                prefs[KEY_ACCESS_TOKEN] = token
            }
        }
    }

    actual suspend fun getRefreshToken(): String? {
        return dataStore.data.map { it[KEY_REFRESH_TOKEN] }.first()
    }

    actual suspend fun setRefreshToken(token: String?) {
        dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(KEY_REFRESH_TOKEN)
            } else {
                prefs[KEY_REFRESH_TOKEN] = token
            }
        }
    }

    actual suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }
}
