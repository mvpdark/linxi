package top.mvpdark.lingxi.ui.imageedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    return remember(onResult) {
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
                val bytes = fileChooser.selectedFile?.readBytes()
                onResult(bytes)
            } else {
                onResult(null)
            }
        }
    }
}
