package top.mvpdark.lingxi.di

import org.koin.dsl.module
import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.core.network.TokenStore
import top.mvpdark.lingxi.sam.SamService

/**
 * iOS 平台 Koin 模块：提供 [TokenStore] 和 [SamService]。
 *
 * 与 Desktop 端一致，[PlatformContext] 为空占位（iOS TokenStore 使用
 * NSUserDefaults.standardUserDefaults，无需额外上下文）。
 */
val platformModule = module {
    single { PlatformContext() }
    single { TokenStore(get()) }
    single { SamService(get()) }
}
