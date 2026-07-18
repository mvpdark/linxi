package top.mvpdark.lingxi.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android 平台上下文：直接 typealias 到 android.content.Context。
 *
 * 这样 [TokenStore] 的构造参数 `context: PlatformContext` 在 Android 上即为 Context，
 * 可直接用于 DataStore 等需要 Context 的 API。
 */
actual typealias PlatformContext = android.content.Context

/** 当前平台标识。 */
actual val currentPlatform: String = "Android"

/**
 * 创建 Android 端 Ktor 引擎（OkHttp）。
 */
actual fun createEngine(): HttpClientEngineFactory<*> = OkHttp
