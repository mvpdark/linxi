package top.mvpdark.lingxi.ui.imageedit

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // 将 IO 密集的读取操作切到 Dispatchers.IO，避免阻塞主线程
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readBytesFromUri(context, uri) }
                onResult(bytes)
            }
        } else {
            onResult(null)
        }
    }
    return remember(launcher) {
        { launcher.launch("image/*") }
    }
}

private fun readBytesFromUri(context: Context, uri: Uri): ByteArray? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.onFailure { e ->
        top.mvpdark.lingxi.core.util.PlatformLogger.e(
            "ImagePicker",
            "Failed to read image from URI: $uri",
            e,
        )
    }.getOrNull()
}
