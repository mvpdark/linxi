package top.mvpdark.lingxi.data.repository

import io.ktor.client.call.body
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import kotlinx.serialization.encodeToString
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.util.runCatchingCancellable
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
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/upload",
                formData = formData {
                    appendFile("file", bytes, fileName)
                },
            ).body<UploadResponse>()
        }.getOrElse { e ->
            // TODO: 对非预期异常返回通用文案，避免泄露内部信息
            UploadResponse(success = false, error = e.message ?: "上传失败")
        }
    }

    /**
     * VLM 物体检测。
     *
     * POST /api/vlm-detect，multipart 字段：file。
     * 后端返回 {success, objects: [{id, label, bbox}]}。
     */
    suspend fun vlmDetect(bytes: ByteArray, fileName: String): VlmDetectResponse {
        return runCatchingCancellable {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/vlm-detect",
                formData = formData {
                    appendFile("file", bytes, fileName)
                },
            ).body<VlmDetectResponse>()
        }.getOrElse { e ->
            // TODO: 对非预期异常返回通用文案，避免泄露内部信息
            VlmDetectResponse(success = false, error = e.message ?: "检测失败")
        }
    }

    /**
     * 直接图生图编辑（无区域标注）。
     *
     * POST /api/image-edit，multipart 字段：file + prompt + resolution + ratio。
     * 后端返回 {success, image: dataUrl, url}。
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
            ).body<ImageEditResponse>()
        }.getOrElse { e ->
            // TODO: 对非预期异常返回通用文案，避免泄露内部信息
            ImageEditResponse(success = false, error = e.message ?: "生成失败")
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
            ).body<ImageEditResponse>()
        }.getOrElse { e ->
            // TODO: 对非预期异常返回通用文案，避免泄露内部信息
            ImageEditResponse(success = false, error = e.message ?: "生成失败")
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

    /** 根据文件扩展名推导 MIME 类型。 */
    private fun guessContentType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            // 未知扩展名使用 image/jpeg 作为兜底（最通用的图片格式），
            // 避免 application/octet-stream 被服务端 415 拒绝
            else -> "image/jpeg"
        }
    }
}
