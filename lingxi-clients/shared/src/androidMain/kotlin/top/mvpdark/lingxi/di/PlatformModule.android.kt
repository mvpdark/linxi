package top.mvpdark.lingxi.di

import org.koin.dsl.module
import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.core.network.TokenStore

/**
 * Android App Context 持有器。
 *
 * 由 androidApp 的 [top.mvpdark.lingxi.android.LingxiApplication] 在 onCreate 中初始化，
 * 供 [platformModule] 在 Koin 启动时读取。
 *
 * 采用此持有器而非 koin-android 的 `androidContext()`，是为了避免在共享模块
 * androidMain 中引入额外依赖；功能等价。
 */
object AndroidAppContextHolder {
    @Volatile
    var context: PlatformContext? = null
}

/**
 * Android 平台 Koin 模块：提供 [TokenStore]（依赖 android.content.Context）。
 *
 * 注意：调用方需在 `startKoin` 前完成 [AndroidAppContextHolder] 的初始化。
 */
val platformModule = module {
    single {
        val ctx = AndroidAppContextHolder.context
            ?: error("AndroidAppContextHolder 未初始化，请在 LingxiApplication.onCreate 中赋值")
        TokenStore(ctx)
    }
}
