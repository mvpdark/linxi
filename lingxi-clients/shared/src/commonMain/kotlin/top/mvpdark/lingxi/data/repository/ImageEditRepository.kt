package top.mvpdark.lingxi.data.repository

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import kotlinx.serialization.encodeToString
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.util.PlatformLogger
import top.mvpdark.lingxi.core.util.runCatchingCancellable
import top.mvpdark.lingxi.core.util.toUserMessage
import top.mvpdark.lingxi.data.model.DetectedObject
import top.mvpdark.lingxi.data.model.EditRegion
import top.mvpdark.lingxi.data.model.ImageEditResponse
import top.mvpdark.lingxi.data.model.UploadResponse
import top.mvpdark.lingxi.data.model.VlmDetectResponse

/**
 * 图像编辑仓库：封装 /api/upload、/api/vlm-detect、/api/image-edit[-annotated] 接口。
 *
 * 所有方法均通过 multipart/form-data 上传图片二进制，响应统一反序列化为
 * 对应的数据模型。失败时用 [runCatchingCancellable] 包裹，返回带 error 字段的响应，
 * 不向调用方抛出异常，便于 UI 层直接展示错误信息。
 *
 * 超时策略：
 * - 普通上传 [/api/upload]：保持全局 30 秒（足够）
 * - VLM 检测 [/api/vlm-detect]：90 秒（AI 推理）
 * - 图生图 [/api/image-edit[-annotated]]：120 秒（AI 生成，后端 600 秒）
 *
 * @param apiClient 共享的 API 客户端（复用 httpClient 与 json）
 */
class ImageEditRepository(
    private val apiClient: ApiClient,
) {
    /**
     * 上传图片。
     *
     * POST /api/upload，multipart 字段：file。
     * 后端返回 {success, image: dataUrl, id, url}。
     */
    suspend fun uploadImage(bytes: ByteArray, fileName: String): UploadResponse {
        return runCatchingCancellable {
            val resp = apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/upload",
                formData = formData {
                    appendFile("file", bytes, fileName)
                },
            ).body<UploadResponse>()
            // 容错：后端 /api/upload 旧版本不返回 success 字段，
            // 以 image 非空作为成功依据（与 Web 端 image-edit.js 对齐）
            if (resp.image.isNotEmpty() && !resp.success) resp.copy(success = true) else resp
        }.getOrElse { e ->
            PlatformLogger.e("ImageEditRepository", "uploadImage failed", e)
            UploadResponse(success = false, error = e.toUserMessage())
        }
    }

    /**
     * VLM 物体检测。
     *
     * POST /api/vlm-detect，multipart 字段：file。
     * 后端返回 {success, objects: [{id, label, bbox}]}。
     *
     * 超时：90 秒（VLM 推理通常 10-30 秒，留足余量）。
     */
    suspend fun vlmDetect(bytes: ByteArray, fileName: String): VlmDetectResponse {
        return runCatchingCancellable {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/vlm-detect",
                formData = formData {
                    appendFile("file", bytes, fileName)
                },
            ) {
                timeout {
                    requestTimeoutMillis = 90_000
                    socketTimeoutMillis = 90_000
                }
            }.body<VlmDetectResponse>()
        }.getOrElse { e ->
            PlatformLogger.e("ImageEditRepository", "vlmDetect failed", e)
            VlmDetectResponse(success = false, error = e.toUserMessage())
        }
    }

    /**
     * 直接图生图编辑（无区域标注）。
     *
     * POST /api/image-edit，multipart 字段：file + prompt + resolution + ratio。
     * 后端返回 {success, image: dataUrl, url}。
     *
     * 超时：120 秒（AI 图生图通常 15-60 秒，留足余量）。
     */
    suspend fun editImage(
        bytes: ByteArray,
        fileName: String,
        prompt: String,
        resolution: String = "1K",
        ratio: String = "1:1",
    ): ImageEditResponse {
        return runCatchingCancellable {
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
            }.body<ImageEditResponse>()
        }.getOrElse { e ->
            PlatformLogger.e("ImageEditRepository", "editImage failed", e)
            ImageEditResponse(success = false, error = e.toUserMessage())
        }
    }

    /**
     * 带区域标注的图生图编辑。
     *
     * POST /api/image-edit-annotated，multipart 字段：
     * file + prompt + regions(JSON 字符串) + resolution + ratio。
     *
     * regions JSON 格式对齐 image-edit.js startEdit：
     * [{"id":1,"label":"猫","bbox":{"x":0.1,"y":0.2,"w":0.3,"h":0.4},
     *   "polygon":[[0.1,0.2],...],"mask_png_b64":"..."}]
     *
     * 超时：120 秒（SAM2 分割 + AI 图生图，耗时较长）。
     *
     * @param regions 选中的物体列表（含 polygon 与 mask_png_b64，若存在）
     */
    suspend fun editImageAnnotated(
        bytes: ByteArray,
        fileName: String,
        prompt: String,
        regions: List<DetectedObject>,
        resolution: String = "1K",
        ratio: String = "1:1",
    ): ImageEditResponse {
        // 将 DetectedObject 转为 EditRegion（只保留后端需要的字段）
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

        return runCatchingCancellable {
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
            }.body<ImageEditResponse>()
        }.getOrElse { e ->
            PlatformLogger.e("ImageEditRepository", "editImageAnnotated failed", e)
            ImageEditResponse(success = false, error = e.toUserMessage())
        }
    }

    /**
     * 向 formData 追加文件二进制（自动根据扩展名推导 Content-Type）。
     *
     * 显式构造完整的 Content-Disposition（含 name 与 filename），确保
     * 后端 python-multipart 能正确解析字段名与文件名。
     */
    private fun FormBuilder.appendFile(
        name: String,
        bytes: ByteArray,
        fileName: String,
    ) {
        // 清洗文件名：移除引号与换行，避免破坏 Content-Disposition 头
        val safeFileName = fileName.replace("\"", "").replace("\r", "").replace("\n", "")
        append(name, bytes, Headers.build {
            append(HttpHeaders.ContentDisposition, "form-data; name=\"$name\"; filename=\"$safeFileName\"")
            append(HttpHeaders.ContentType, guessContentType(safeFileName))
        })
    }

    /**
     * 根据文件扩展名推导 MIME 类型。
     *
     * 仅返回后端 [ALLOWED_TYPES] 支持的格式（jpeg/png/webp），
     * 其他扩展名统一兜底为 image/jpeg，避免 gif/bmp 被后端 415 拒绝。
     */
    private fun guessContentType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            // 后端仅允许 jpeg/png/webp，其他统一兜底为 jpeg
            else -> "image/jpeg"
        }
    }
}
