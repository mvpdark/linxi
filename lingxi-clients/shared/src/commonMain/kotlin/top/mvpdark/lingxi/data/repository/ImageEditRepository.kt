package top.mvpdark.lingxi.data.repository

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.encodeToString
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.util.PlatformLogger
import top.mvpdark.lingxi.core.util.runCatchingCancellable
import top.mvpdark.lingxi.core.util.sanitizeMultipartFileName
import top.mvpdark.lingxi.core.util.toUserMessage
import top.mvpdark.lingxi.data.model.DetectedObject
import top.mvpdark.lingxi.data.model.EditRegion
import top.mvpdark.lingxi.data.model.ImageEditResponse
import top.mvpdark.lingxi.data.model.SamSegmentRequestObject
import top.mvpdark.lingxi.data.model.SamSegmentResponse
import top.mvpdark.lingxi.data.model.UploadResponse
import top.mvpdark.lingxi.data.model.VlmDetectResponse
import top.mvpdark.lingxi.data.model.Bbox
import top.mvpdark.lingxi.sam.SamObject
import top.mvpdark.lingxi.sam.SamSegmentResult

/**
 * 图像编辑仓库：封装 /api/upload、/api/vlm-detect、/api/image-edit[-annotated] 接口。
 *
 * 鉴权策略：
 * - 收到 401 时自动用 refresh_token 刷新并重试（限 1 次）
 * - 刷新失败返回"登录已过期，请重新登录"错误
 * - 所有 HTTP 错误码本地化为中文文案，不泄露后端英文错误
 *
 * 超时策略：
 * - 普通上传 [/api/upload]：30 秒
 * - VLM 检测 [/api/vlm-detect]：90 秒
 * - 图生图 [/api/image-edit[-annotated]]：120 秒
 */
class ImageEditRepository(
    private val apiClient: ApiClient,
) {
    /**
     * 上传图片。POST /api/upload，multipart 字段：file。
     */
    suspend fun uploadImage(bytes: ByteArray, fileName: String): UploadResponse {
        return requestWithAuthRetry(
            onError = { UploadResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/upload",
                formData = formData { appendFile("file", bytes, fileName) },
            )
        }.let { resp ->
            // 容错：后端旧版本不返回 success，以 image 非空为成功依据
            if (resp.image.isNotEmpty() && !resp.success) resp.copy(success = true) else resp
        }
    }

    /**
     * VLM 物体检测。POST /api/vlm-detect。超时 90 秒。
     */
    suspend fun vlmDetect(bytes: ByteArray, fileName: String): VlmDetectResponse {
        return requestWithAuthRetry(
            onError = { VlmDetectResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/vlm-detect",
                formData = formData { appendFile("file", bytes, fileName) },
            ) {
                timeout {
                    requestTimeoutMillis = 90_000
                    socketTimeoutMillis = 90_000
                }
            }
        }
    }

    /**
     * 直接图生图编辑。POST /api/image-edit。超时 120 秒。
     */
    suspend fun editImage(
        bytes: ByteArray,
        fileName: String,
        prompt: String,
        resolution: String = "1K",
        ratio: String = "1:1",
    ): ImageEditResponse {
        return requestWithAuthRetry(
            onError = { ImageEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/image-edit",
                formData = formData {
                    appendFile("file", bytes, fileName)
                    append("prompt", prompt)
                    append("resolution", resolution)
                    append("ratio", ratio)
                },
            ) {
                timeout {
                    requestTimeoutMillis = 120_000
                    socketTimeoutMillis = 120_000
                }
            }
        }
    }

    /**
     * 带区域标注的图生图编辑。POST /api/image-edit-annotated。超时 120 秒。
     */
    suspend fun editImageAnnotated(
        bytes: ByteArray,
        fileName: String,
        prompt: String,
        regions: List<DetectedObject>,
        resolution: String = "1K",
        ratio: String = "1:1",
    ): ImageEditResponse {
        val editRegions = regions.map { obj ->
            EditRegion(
                id = obj.id,
                label = obj.label,
                bbox = obj.bbox,
                polygon = obj.polygon,
                maskPngB64 = obj.maskPngB64,
            )
        }
        val regionsJson = apiClient.json.encodeToString(editRegions)

        return requestWithAuthRetry(
            onError = { ImageEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/image-edit-annotated",
                formData = formData {
                    appendFile("file", bytes, fileName)
                    append("prompt", prompt)
                    append("regions", regionsJson)
                    append("resolution", resolution)
                    append("ratio", ratio)
                },
            ) {
                timeout {
                    requestTimeoutMillis = 120_000
                    socketTimeoutMillis = 120_000
                }
            }
        }
    }

    /**
     * 后端 SAM 2 分割（精确轮廓提取）。POST /api/sam-segment。超时 60 秒。
     *
     * 后端 SAM 2 使用 sam2.1_hiera_tiny.pt checkpoint，CPU 推理 2-5 秒。
     * 相比端侧 ONNX 模型，后端 SAM 2 无需客户端下载模型文件，更可靠。
     *
     * @param bytes 原图字节流
     * @param fileName 文件名
     * @param objects 待分割物体列表 (id, bbox)，bbox 为归一化坐标 0-1
     * @return SamSegmentResult，成功时 objects 含 polygon 和 maskPngB64
     */
    suspend fun samSegment(
        bytes: ByteArray,
        fileName: String,
        objects: List<Pair<Int, Bbox>>,
    ): SamSegmentResult {
        // 构建后端期望的 objects JSON：[{"id":1,"label":"","bbox":[x,y,w,h]}, ...]
        val requestObjects = objects.map { (id, bbox) ->
            SamSegmentRequestObject(
                id = id,
                label = "",
                bbox = listOf(bbox.x, bbox.y, bbox.w, bbox.h),
            )
        }
        val objectsJson = apiClient.json.encodeToString(requestObjects)

        val response: SamSegmentResponse = requestWithAuthRetry(
            onError = { SamSegmentResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/sam-segment",
                formData = formData {
                    appendFile("file", bytes, fileName)
                    append("objects", objectsJson)
                },
            ) {
                timeout {
                    requestTimeoutMillis = 60_000
                    socketTimeoutMillis = 60_000
                }
            }
        }

        if (!response.success) {
            return SamSegmentResult(success = false, error = response.error)
        }

        // 后端返回的 objects 列表顺序与输入一致，用 index 匹配 id
        return SamSegmentResult(
            success = true,
            objects = response.objects.mapIndexed { idx, obj ->
                SamObject(
                    id = objects.getOrNull(idx)?.first ?: idx,
                    polygon = if (obj.polygon.isNotEmpty()) {
                        obj.polygon.mapNotNull { pair ->
                            if (pair.size >= 2) pair[0] to pair[1] else null
                        }.takeIf { it.isNotEmpty() }
                    } else null,
                    maskPngB64 = obj.maskPngB64.ifEmpty { null },
                )
            },
        )
    }

    /**
     * 带鉴权重试的请求包装器。
     *
     * 流程：
     * 1. 发起请求
     * 2. 如果 401 → 用 refresh_token 刷新 → 重试一次
     * 3. 刷新失败 → 返回"登录已过期"错误
     * 4. 其他错误 → 返回本地化错误
     * 5. 成功 → 反序列化响应
     *
     * @param onError 错误回调，接收本地化错误文案，返回带 error 字段的默认对象
     * @param block 请求执行块
     */
    private suspend inline fun <reified T> requestWithAuthRetry(
        noinline onError: (String) -> T,
        noinline block: suspend () -> HttpResponse,
    ): T {
        // 第一次尝试
        val firstResponse = try {
            block()
        } catch (e: Exception) {
            PlatformLogger.e("ImageEditRepository", "Request failed", e)
            return onError(e.toUserMessage())
        }

        // 成功：反序列化
        if (firstResponse.status.isSuccess()) {
            return try {
                firstResponse.body<T>()
            } catch (e: Exception) {
                PlatformLogger.e("ImageEditRepository", "Parse failed", e)
                onError(e.toUserMessage())
            }
        }

        // 401：尝试刷新 token 并重试
        if (firstResponse.status.value == 401) {
            val refreshed = apiClient.ensureValidToken()
            if (refreshed) {
                // 重试一次
                val retryResponse = try {
                    block()
                } catch (e: Exception) {
                    PlatformLogger.e("ImageEditRepository", "Retry failed", e)
                    return onError(e.toUserMessage())
                }
                if (retryResponse.status.isSuccess()) {
                    return try {
                        retryResponse.body<T>()
                    } catch (e: Exception) {
                        onError(e.toUserMessage())
                    }
                }
                return onError(localizeHttpError(retryResponse.status.value))
            } else {
                return onError("登录已过期，请重新登录")
            }
        }

        // 其他 HTTP 错误
        return onError(localizeHttpError(firstResponse.status.value))
    }

    /** HTTP 状态码 → 中文文案。 */
    private fun localizeHttpError(statusCode: Int): String = when (statusCode) {
        401 -> "登录已过期，请重新登录"
        403 -> "无权限访问"
        404 -> "请求的资源不存在"
        413 -> "图片过大，请压缩后重试"
        415 -> "不支持的图片格式"
        429 -> "操作过于频繁，请稍后再试"
        in 500..599 -> "服务器开小差了，请稍后重试"
        else -> "请求失败（$statusCode）"
    }

    private fun FormBuilder.appendFile(
        name: String,
        bytes: ByteArray,
        fileName: String,
    ) {
        val safeFileName = sanitizeMultipartFileName(fileName)
        append(name, bytes, Headers.build {
            append(HttpHeaders.ContentDisposition, "form-data; name=\"$name\"; filename=\"$safeFileName\"")
            append(HttpHeaders.ContentType, guessContentType(safeFileName))
        })
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
