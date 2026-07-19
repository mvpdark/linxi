package top.mvpdark.lingxi.ui.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.mvpdark.lingxi.core.network.createEngine
import top.mvpdark.lingxi.core.util.PlatformLogger
import top.mvpdark.lingxi.di.AndroidAppContextHolder
import java.io.File
import java.io.FileOutputStream

/**
 * Android 平台 APK 安装器。
 *
 * 下载 APK 到 cacheDir，通过 FileProvider + ACTION_VIEW 触发系统安装。
 */
actual class ApkInstaller actual constructor() {

    private val context: Context
        get() = AndroidAppContextHolder.context?.androidContext
            ?: throw IllegalStateException("AndroidAppContextHolder not initialized")

    actual suspend fun downloadAndInstall(
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        try {
            val apkFile = File(context.cacheDir, "lingxi-update.apk")
            if (apkFile.exists()) apkFile.delete()

            withContext(Dispatchers.IO) {
                val client = HttpClient(createEngine()) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 120_000
                        connectTimeoutMillis = 15_000
                        socketTimeoutMillis = 120_000
                    }
                }
                client.use {
                    val response: HttpResponse = it.get(downloadUrl)
                    val channel: ByteReadChannel = response.body()
                    val totalBytes = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                    var downloadedBytes = 0L
                    val buffer = ByteArray(8192)

                    FileOutputStream(apkFile).use { out ->
                        while (true) {
                            val read = channel.readAvailable(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                onProgress(progress)
                            }
                        }
                    }
                }
            }

            onProgress(100)

            // 触发系统安装
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }

            onComplete(true)
        } catch (e: Exception) {
            PlatformLogger.e(
                "ApkInstaller",
                "Download/install failed",
                e,
            )
            onComplete(false)
        }
    }
}
