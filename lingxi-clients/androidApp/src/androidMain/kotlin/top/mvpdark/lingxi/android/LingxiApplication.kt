package top.mvpdark.lingxi.android

import android.app.Application
import org.koin.core.context.startKoin
import top.mvpdark.lingxi.di.AndroidAppContextHolder
import top.mvpdark.lingxi.di.appModule
import top.mvpdark.lingxi.di.platformModule

/**
 * Android Application 入口。
 *
 * 在 [onCreate] 中：
 * 1. 注入 Application Context 到 [AndroidAppContextHolder]（供 platformModule 读取）
 * 2. 启动 Koin，注册 appModule + platformModule
 */
class LingxiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 注入平台 Context（PlatformContext 在 Android 上即 Context）
        AndroidAppContextHolder.context = this
        // 启动 Koin
        startKoin {
            modules(appModule, platformModule)
        }
    }
}
