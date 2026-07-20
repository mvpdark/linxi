package top.mvpdark.lingxi.data.local

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.mvpdark.lingxi.data.model.ChatMessage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 图片缓存管理器：负责将网络图片 URL 下载到本地缓存。
 *
 * 处理三种图片来源：
 * - `http://` / `https://`：用 [HttpClient] 下载字节流，调用 [LocalMessageStore.saveImage] 得到本地路径
 * - `data:`：解码 base64 得到字节流，以原始 data URL 的 MD5 为 key 保存到本地
 * - `file://`：已是本地路径，直接返回原值
 *
 * 多张图片通过 `async` / `awaitAll` 并行下载。单张下载失败时回退为原始 URL，
 * 保证消息内容不被丢失。
 *
 * @param httpClient 用于下载网络图片的 Ktor 客户端
 * @param localStore 本地持久化存储
 */
class ImageCacheManager(
    private val httpClient: HttpClient,
    private val localStore: LocalMessageStore,
) {
    /**
     * 处理消息中的图片：如果是网络 URL 则下载到本地，返回本地路径。
     * 已是本地路径（`file://`）的直接返回原值。
     *
     * @param images 原始图片 URL 列表
     * @return 处理后的本地路径列表（与输入一一对应）
     */
    suspend fun cacheImages(images: List<String>): List<String> {
        if (images.isEmpty()) return emptyList()
        return coroutineScope {
            images.map { url ->
                async { cacheSingleImage(url) }
            }.awaitAll()
        }
    }

    /**
     * 批量处理消息列表中的所有图片，返回替换为本地路径的新消息列表。
     *
     * 无图片的消息原样保留，不进行拷贝。
     *
     * @param messages 原始消息列表
     * @return 图片路径已本地化的消息列表
     */
    suspend fun cacheMessagesImages(messages: List<ChatMessage>): List<ChatMessage> {
        return messages.map { msg ->
            if (msg.images.isEmpty()) msg
            else msg.copy(images = cacheImages(msg.images))
        }
    }

    /**
     * 处理单张图片：下载并缓存到本地，返回本地路径。
     * 下载或保存失败时返回原始 URL，保证消息可用性。
     */
    private suspend fun cacheSingleImage(url: String): String {
        return try {
            when {
                url.startsWith("file://") -> url

                url.startsWith("http://") || url.startsWith("https://") -> {
                    val response = httpClient.get(url)
                    // 校验 HTTP 状态码：错误响应（如 404 错误页）的字节一旦写入缓存，
                    // 其本地路径会被持久化到消息中，导致坏缓存永远命中
                    if (!response.status.isSuccess()) {
                        error("HTTP ${response.status.value}: $url")
                    }
                    val bytes = response.bodyAsBytes()
                    localStore.saveImage(url, bytes)
                }

                url.startsWith("data:") -> {
                    val bytes = decodeDataUrl(url)
                    localStore.saveImage(url, bytes)
                }

                else -> url
            }
        } catch (e: CancellationException) {
            // 协程取消不能吞掉：否则父作用域取消时下载任务无法及时停止
            throw e
        } catch (e: Exception) {
            // 下载或保存失败时返回原始 URL，保证消息内容不丢失
            url
        }
    }

    /**
     * 解码 data URL（`data:image/jpeg;base64,...`）为字节流。
     *
     * 取逗号后的 base64 部分进行解码，MIME 前缀忽略（统一保存为 .jpg）。
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val commaIdx = dataUrl.indexOf(',')
        val base64 = if (commaIdx >= 0) dataUrl.substring(commaIdx + 1) else dataUrl
        return Base64.decode(base64)
    }
}
