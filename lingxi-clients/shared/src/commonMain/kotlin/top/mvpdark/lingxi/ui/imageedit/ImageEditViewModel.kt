package top.mvpdark.lingxi.ui.imageedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.mvpdark.lingxi.data.model.Bbox
import top.mvpdark.lingxi.data.model.DetectedObject
import top.mvpdark.lingxi.data.repository.ImageEditRepository
import top.mvpdark.lingxi.sam.SamService

/**
 * 图像编辑 UI 状态。
 *
 * 状态机对齐 image-edit.js 的 step：upload / analyzing / segmenting / edit / generating / result。
 * 其中 analyzing / segmenting / generating 为瞬态加载步骤，edit 为核心交互步骤。
 *
 * @property step 当前流程步骤
 * @property originalBytes 原始图片字节流（用于后续 API 调用）
 * @property imageDisplayUrl 用于 Coil 显示的 data URL
 * @property objects VLM 检测 + SAM 分割后的物体列表
 * @property promptText 未选中物体时的全局改图描述
 * @property selectedPrompt 选中物体时的区域改图描述
 * @property resultUrl 生成结果图 URL（data URL）
 * @property error 错误提示文案
 * @property samLoading SAM 模型是否加载中
 * @property samProgress SAM 模型加载进度（0-100）
 * @property isEditing 是否正在生成改图
 */
@Suppress("ArrayInDataClass")
data class ImageEditUiState(
    val step: ImageEditViewModel.Step = ImageEditViewModel.Step.Upload,
    val originalBytes: ByteArray? = null,
    val imageDisplayUrl: String? = null,
    val objects: List<DetectedObject> = emptyList(),
    val promptText: String = "",
    val selectedPrompt: String = "",
    val resultUrl: String? = null,
    val error: String? = null,
    val samLoading: Boolean = false,
    val samProgress: Int = 0,
    val isEditing: Boolean = false,
)

/**
 * 图像编辑 ViewModel：管理上传 → VLM 检测 → SAM 分割 → 编辑 → 生成 的完整流程。
 *
 * 通过 Koin 注入 [ImageEditRepository] 与 [SamService]。
 * 状态机对齐前端 image-edit.js，支持物体选中、区域标注改图与直接改图两种模式。
 *
 * 流程说明：
 * 1. [onPickImage] — 用户选图后触发，并行执行上传与 VLM 检测，随后跑 SAM 分割
 * 2. [toggleSelect] — 在 Edit 步骤切换物体选中状态
 * 3. [onPromptChange] — 更新改图描述（区分选中 / 未选中场景）
 * 4. [startEdit] — 触发改图生成（有选中走 annotated 接口，无选中走普通接口）
 * 5. [resetEdit] / [resetAll] — 重置编辑或全部状态
 */
class ImageEditViewModel(
    private val repository: ImageEditRepository,
    private val samService: SamService,
) : ViewModel() {

    /** 流程步骤枚举，对齐 image-edit.js 的 step 字段。 */
    enum class Step { Upload, Analyzing, Segmenting, Edit, Generating, Result }

    private val _uiState = MutableStateFlow(ImageEditUiState())
    val uiState: StateFlow<ImageEditUiState> = _uiState.asStateFlow()

    /** 重入保护：防止 onPickImage / startEdit 被并发调用导致状态错乱。 */
    private val isProcessing = kotlin.concurrent.AtomicBoolean(false)

    /**
     * 用户选图后调用：上传 → VLM 检测 → SAM 分割 → 进入编辑模式。
     *
     * 上传与 VLM 检测并行执行（对齐 image-edit.js 的 Promise.all）。
     * SAM 分割失败时回退到 bbox 模式，不阻塞流程。
     *
     * @param bytes 原始图片字节流
     */
    fun onPickImage(bytes: ByteArray) {
        // 重入保护：AtomicBoolean 原子抢占，防止并发调用
        if (!isProcessing.compareAndSet(false, true)) return
        // 二次检查：正在处理中的步骤不接受新图片
        val currentStep = _uiState.value.step
        if (currentStep == Step.Analyzing || currentStep == Step.Segmenting || currentStep == Step.Generating) {
            isProcessing.set(false)
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        step = Step.Analyzing,
                        originalBytes = bytes,
                        error = null,
                        samProgress = 0,
                    )
                }

                // 并行：上传 + VLM 检测
                val (uploadResult, detectResult) = coroutineScope {
                    val uploadDeferred = async { repository.uploadImage(bytes, "image.jpg") }
                    val detectDeferred = async { repository.vlmDetect(bytes, "image.jpg") }
                    uploadDeferred.await() to detectDeferred.await()
                }

                val displayUrl = if (uploadResult.success) uploadResult.image else null
                val detectedObjects = if (detectResult.success) {
                    detectResult.objects.mapIndexed { idx, obj ->
                        DetectedObject(
                            id = obj.id ?: idx,
                            label = obj.label,
                            bbox = obj.bbox ?: Bbox(0f, 0f, 0f, 0f),
                        )
                    }
                } else {
                    emptyList()
                }

                if (displayUrl == null) {
                    _uiState.update {
                        it.copy(
                            step = Step.Upload,
                            error = uploadResult.error.ifEmpty { "图片上传失败" },
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        imageDisplayUrl = displayUrl,
                        objects = detectedObjects,
                    )
                }

                // 如果有检测到物体，跑 SAM 分割
                if (detectedObjects.isNotEmpty()) {
                    _uiState.update {
                        it.copy(step = Step.Segmenting, samLoading = true)
                    }

                    // 加载 SAM 模型（如果未加载），用 runCatching 防止加载失败阻塞流程
                    if (!samService.isReady) {
                        runCatching {
                            samService.loadModel { progress, _ ->
                                _uiState.update { it.copy(samProgress = progress) }
                            }
                        }.onFailure { e ->
                            println("[ImageEditViewModel] SAM loadModel failed: ${e.message}")
                        }
                    }

                    // 执行分割，失败时回退 bbox 模式
                    val samResult = runCatching {
                        samService.segment(bytes, detectedObjects.map { it.id to it.bbox })
                    }.getOrNull()

                    if (samResult != null && samResult.success) {
                        // 用 polygon 更新 objects
                        val updatedObjects = detectedObjects.map { obj ->
                            val samObj = samResult.objects.find { it.id == obj.id }
                            if (samObj != null) {
                                obj.copy(
                                    polygon = samObj.polygon?.map { listOf(it.first, it.second) },
                                    maskPngB64 = samObj.maskPngB64,
                                )
                            } else {
                                obj
                            }
                        }
                        _uiState.update {
                            it.copy(objects = updatedObjects, samLoading = false, samProgress = 0)
                        }
                    } else {
                        // SAM 失败，回退 bbox 模式
                        _uiState.update { it.copy(samLoading = false, samProgress = 0) }
                    }
                }

                _uiState.update { it.copy(step = Step.Edit) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        step = Step.Upload,
                        samLoading = false,
                        samProgress = 0,
                        error = e.message ?: "图片处理失败",
                    )
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    /**
     * 切换物体选中状态。
     *
     * @param id 物体 ID
     */
    fun toggleSelect(id: Int) {
        _uiState.update {
            it.copy(
                objects = it.objects.map { obj ->
                    if (obj.id == id) obj.copy(selected = !obj.selected) else obj
                },
            )
        }
    }

    /**
     * 更新改图描述。
     *
     * @param text 输入文本
     * @param selected 是否为选中物体的输入框（true 更新 selectedPrompt，false 更新 promptText）
     */
    fun onPromptChange(text: String, selected: Boolean) {
        _uiState.update {
            if (selected) {
                it.copy(selectedPrompt = text)
            } else {
                it.copy(promptText = text)
            }
        }
    }

    /**
     * 开始改图：收集选中区域 + prompt → 调用 API → 显示结果。
     *
     * - 有选中物体时走 [ImageEditRepository.editImageAnnotated]（带区域标注）
     * - 无选中物体时走 [ImageEditRepository.editImage]（直接改图）
     */
    fun startEdit() {
        // 重入保护：AtomicBoolean 原子抢占，防止并发触发改图
        if (!isProcessing.compareAndSet(false, true)) return
        val state = _uiState.value
        val bytes = state.originalBytes
        if (bytes == null) {
            isProcessing.set(false)
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(step = Step.Generating, isEditing = true, error = null)
                }

                val selectedObjects = state.objects.filter { it.selected }
                val result = if (selectedObjects.isNotEmpty()) {
                    // 有选中物体：带区域标注改图
                    val prompt = state.selectedPrompt.ifEmpty { "优化选中区域" }
                    repository.editImageAnnotated(bytes, "image.jpg", prompt, selectedObjects)
                } else {
                    // 无选中：直接改图
                    val prompt = state.promptText.ifEmpty { "优化图片" }
                    repository.editImage(bytes, "image.jpg", prompt)
                }

                if (result.success && result.image.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            step = Step.Result,
                            resultUrl = result.image,
                            isEditing = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            step = Step.Edit,
                            isEditing = false,
                            error = result.error.ifEmpty { "改图失败，请重试" },
                        )
                    }
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    /** 重新编辑 — 回到编辑步骤，保留图片和标注，清除结果与选中描述。 */
    fun resetEdit() {
        _uiState.update {
            it.copy(
                step = Step.Edit,
                resultUrl = null,
                selectedPrompt = "",
                error = null,
            )
        }
    }

    /** 重新上传 — 重置所有状态，回到上传步骤。 */
    fun resetAll() {
        _uiState.value = ImageEditUiState()
    }

    /**
     * 重新开始：重置所有状态，回到上传步骤。
     *
     * 注意：理想语义应为"将结果图作为新底图继续编辑"，但 commonMain 不能直接用
     * java.util.Base64 解码 data URL 为 ByteArray，无法将结果图设为新的 originalBytes。
     * 当前实现先重置到上传步骤，让用户重新选图；UI 侧按钮文案已对齐为"重新开始"。
     * 后续可通过 expect/actual 提供平台相关的 Base64 解码实现，再恢复"继续编辑"语义。
     */
    fun continueEdit() {
        _uiState.value = ImageEditUiState()
    }

    /** 清除错误提示。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
