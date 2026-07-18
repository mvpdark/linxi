package top.mvpdark.lingxi.ui.imageedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    // remember(Unit) 避免 onResult 变化导致 remember 失效
    return remember(Unit) {
        {
            val fileChooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter(
                    "Images (jpg, png, webp, gif, bmp)",
                    "jpg", "jpeg", "png", "webp", "gif", "bmp",
                )
                isMultiSelectionEnabled = false
            }
            val result = fileChooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val selectedFile = fileChooser.selectedFile
                // 将 IO 密集的读取操作切到 Dispatchers.IO，避免阻塞 UI 线程
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        selectedFile?.readBytes()
                    }
                    onResult(bytes)
                }
            } else {
                onResult(null)
            }
        }
    }
}
