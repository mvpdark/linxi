package top.mvpdark.lingxi.di

import org.koin.dsl.module
import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.core.network.TokenStore
import top.mvpdark.lingxi.data.local.LocalMessageStore
import top.mvpdark.lingxi.sam.SamService

/**
 * Desktop 平台 Koin 模块：提供 [TokenStore] 和 [SamService]。
 */
val platformModule = module {
    single { PlatformContext() }
    single { TokenStore(get()) }
    single { SamService(get()) }
    single { LocalMessageStore(get()) }
}
