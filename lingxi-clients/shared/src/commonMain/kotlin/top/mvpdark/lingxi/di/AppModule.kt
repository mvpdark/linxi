package top.mvpdark.lingxi.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.util.UrlResolver
import top.mvpdark.lingxi.data.repository.AuthRepository
import top.mvpdark.lingxi.data.repository.ChatRepository
import top.mvpdark.lingxi.data.repository.ImageEditRepository
import top.mvpdark.lingxi.ui.auth.AuthViewModel
import top.mvpdark.lingxi.ui.chat.ChatViewModel
import top.mvpdark.lingxi.ui.imageedit.ImageEditViewModel

/**
 * 应用级 Koin 模块：注册共享网络层、仓库与 ViewModel。
 *
 * 平台相关依赖（[top.mvpdark.lingxi.core.network.TokenStore]、[top.mvpdark.lingxi.sam.SamService]）
 * 由各平台的 `platformModule` 提供（见 shared/src/androidMain 与 desktopMain）。
 *
 * 注意：调用方需在 `startKoin { }` 中以 `modules(appModule, platformModule)` 注册。
 */
val appModule = module {
    // baseUrl 可由 platformModule 以 named("baseUrl") 覆盖；默认使用 UrlResolver.BASE_URL
    val baseUrlQualifier = named("baseUrl")

    // 网络客户端
    single {
        ApiClient(
            tokenStore = get(),
            baseUrl = getOrNull(baseUrlQualifier) ?: UrlResolver.BASE_URL,
        )
    }

    // 仓库
    singleOf(::AuthRepository)
    singleOf(::ChatRepository)
    singleOf(::ImageEditRepository)

    // ViewModel
    viewModel { AuthViewModel(get()) }
    viewModel { ChatViewModel(get()) }
    viewModel { ImageEditViewModel(get(), get()) }
}
