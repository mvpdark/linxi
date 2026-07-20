package top.mvpdark.lingxi.data.repository

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.util.PlatformLogger
import top.mvpdark.lingxi.core.util.runCatchingCancellable
import top.mvpdark.lingxi.core.util.toUserMessage

/**
 * 全景图生成响应。
 */
@Serializable
data class PanoramaResponse(
    val image: String = "",
    val id: String = "",
    val error: String = "",
    val success: Boolean = true,
)

/**
 * 全景图仓库：封装 /api/panorama/ai-generate 接口。
 *
 * 上传户型图 + 风格描述，后端调用 AI 生成 2:1 equirectangular 全景图。
 */
class PanoramaRepository(
    private val apiClient: ApiClient,
) {
    /**
     * AI 生成全景图。
     *
     * POST /api/panorama/ai-generate，multipart 字段：floor_plan + style_desc。
     * 后端返回 {image: dataUrl, id: pano_id}。
     *
     * 超时：AI 生成通常需要 30-180 秒，后端 image_service 超时设为 600 秒，
     * 客户端 per-request 设置 600 秒以匹配后端，避免后端仍在生成时客户端已超时报错。
     */
    suspend fun aiGenerate(
        floorPlanBytes: ByteArray,
        fileName: String = "floor_plan.jpg",
        styleDesc: String = "现代北欧风格",
    ): PanoramaResponse {
        return runCatchingCancellable {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/panorama/ai-generate",
                formData = formData {
                    append("floor_plan", floorPlanBytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"floor_plan\"; filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, guessContentType(fileName))
                    })
                    append("style_desc", styleDesc)
                },
            ) {
                timeout {
                    requestTimeoutMillis = 600_000
                    socketTimeoutMillis = 600_000
                }
            }.body<PanoramaResponse>()
        }.getOrElse { e ->
            PlatformLogger.e("PanoramaRepository", "aiGenerate failed", e)
            PanoramaResponse(success = false, error = e.toUserMessage())
        }
    }

    private fun guessContentType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
