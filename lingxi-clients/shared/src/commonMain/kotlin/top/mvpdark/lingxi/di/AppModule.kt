package top.mvpdark.lingxi.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.network.createEngine
import top.mvpdark.lingxi.core.util.ImageSaver
import top.mvpdark.lingxi.core.util.UrlResolver
import top.mvpdark.lingxi.data.local.ImageCacheManager
import top.mvpdark.lingxi.data.repository.AuthRepository
import top.mvpdark.lingxi.data.repository.ChatRepository
import top.mvpdark.lingxi.data.repository.ImageEditRepository
import top.mvpdark.lingxi.data.repository.PanoramaRepository
import top.mvpdark.lingxi.data.repository.UpdateRepository
import top.mvpdark.lingxi.ui.auth.AuthViewModel
import top.mvpdark.lingxi.ui.chat.ChatViewModel
import top.mvpdark.lingxi.ui.imageedit.ImageEditViewModel
import top.mvpdark.lingxi.ui.panorama.PanoramaViewModel
import top.mvpdark.lingxi.ui.update.UpdateViewModel

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
    singleOf(::PanoramaRepository)
    singleOf(::UpdateRepository)

    // 图片下载专用 HttpClient（无 JSON 协商、无鉴权插件，用于下载网络图片到本地缓存）
    single(named("imageHttpClient")) {
        HttpClient(createEngine()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    // 本地消息与图片缓存管理器（依赖 LocalMessageStore，由 platformModule 提供）
    single { ImageCacheManager(get(named("imageHttpClient")), get()) }

    // 跨平台图片保存工具（依赖 PlatformContext，由 platformModule 提供）
    single { ImageSaver(get()) }

    // ViewModel
    viewModel { AuthViewModel(get()) }
    viewModel { ChatViewModel(get(), get(), get()) }
    viewModel { ImageEditViewModel(get(), get()) }
    viewModel { PanoramaViewModel(get(), get()) }
    viewModel { UpdateViewModel(get()) }
}
