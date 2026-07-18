package top.mvpdark.lingxi.di

import org.koin.dsl.module
import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.core.network.TokenStore

/**
 * Desktop 平台 Koin 模块：提供 [TokenStore]（基于 Preferences，无需平台上下文）。
 */
val platformModule = module {
    single { TokenStore(PlatformContext()) }
}
