package top.mvpdark.lingxi.ui.panorama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.mvpdark.lingxi.core.util.EncodeUtils
import top.mvpdark.lingxi.core.util.PlatformLogger
import top.mvpdark.lingxi.core.util.toUserMessage
import top.mvpdark.lingxi.data.repository.PanoramaRepository

/**
 * 全景图 UI 状态。
 */
@Suppress("ArrayInDataClass")
data class PanoramaUiState(
    val step: PanoramaViewModel.Step = PanoramaViewModel.Step.Upload,
    val originalBytes: ByteArray? = null,
    val imageDisplayUrl: String? = null,
    val styleDesc: String = "现代北欧风格",
    val resultUrl: String? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
)

/**
 * 全景图 ViewModel：管理上传户型图 → 输入风格 → AI 生成全景图 的流程。
 */
class PanoramaViewModel(
    private val repository: PanoramaRepository,
) : ViewModel() {

    enum class Step { Upload, Edit, Generating, Result }

    private val _uiState = MutableStateFlow(PanoramaUiState())
    val uiState: StateFlow<PanoramaUiState> = _uiState.asStateFlow()

    private val isProcessing = MutableStateFlow(false)

    /** 用户选择户型图后调用。 */
    fun onPickImage(bytes: ByteArray) {
        if (!isProcessing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val dataUrl = EncodeUtils.bytesToDataUrl(bytes)
                _uiState.update {
                    it.copy(
                        step = Step.Edit,
                        originalBytes = bytes,
                        imageDisplayUrl = dataUrl,
                        error = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("PanoramaViewModel", "onPickImage failed", e)
                _uiState.update {
                    it.copy(error = e.toUserMessage())
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /** 更新风格描述。 */
    fun onStyleChange(text: String) {
        _uiState.update { it.copy(styleDesc = text) }
    }

    /** 开始生成全景图。 */
    fun startGenerate() {
        if (!isProcessing.compareAndSet(false, true)) return
        val state = _uiState.value
        val bytes = state.originalBytes
        if (bytes == null) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(step = Step.Generating, isGenerating = true, error = null)
                }

                val result = repository.aiGenerate(
                    floorPlanBytes = bytes,
                    styleDesc = state.styleDesc.ifBlank { "现代北欧风格" },
                )

                if (result.success && result.image.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            step = Step.Result,
                            resultUrl = result.image,
                            isGenerating = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            step = Step.Edit,
                            isGenerating = false,
                            error = result.error.ifEmpty { "全景图生成失败，请重试" },
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("PanoramaViewModel", "startGenerate failed", e)
                _uiState.update {
                    it.copy(
                        step = Step.Edit,
                        isGenerating = false,
                        error = e.toUserMessage(),
                    )
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /** 重新编辑。 */
    fun resetEdit() {
        _uiState.update {
            it.copy(step = Step.Edit, resultUrl = null, error = null)
        }
    }

    /** 重新上传。 */
    fun resetAll() {
        _uiState.value = PanoramaUiState()
    }

    /** 清除错误。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
