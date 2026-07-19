package top.mvpdark.lingxi.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.mvpdark.lingxi.core.network.PlatformContext
import top.mvpdark.lingxi.data.model.ChatMessage
import java.io.File
import java.security.MessageDigest

/**
 * Android 平台 LocalMessageStore：基于应用内部存储目录 + JSON 文件。
 *
 * - 消息存储：`context.filesDir/messages/{sessionId}.json`
 * - 图片存储：`context.filesDir/images/{md5(url)}.jpg`
 *
 * 文件读写通过 [Mutex] 保证线程安全，IO 操作切换到 [Dispatchers.IO]。
 *
 * @param context Android Context（[PlatformContext] 在 Android 上即 Context）
 */
actual class LocalMessageStore actual constructor(context: PlatformContext) {

    private val ctx: Context = context.androidContext

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 消息列表的序列化器，避免反射。 */
    private val messageListSerializer = ListSerializer(ChatMessage.serializer())

    /** 保护同一会话文件读写的互斥锁。 */
    private val mutex = Mutex()

    private val messagesDir: File get() = File(ctx.filesDir, "messages").apply { mkdirs() }
    private val imagesDir: File get() = File(ctx.filesDir, "images").apply { mkdirs() }

    private fun messageFile(sessionId: String): File = File(messagesDir, "$sessionId.json")

    actual suspend fun saveMessage(sessionId: String, message: ChatMessage) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = messageFile(sessionId)
                val existing = if (file.exists()) {
                    runCatching {
                        json.decodeFromString(messageListSerializer, file.readText())
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                file.writeText(json.encodeToString(messageListSerializer, existing + message))
            }
        }
    }

    actual suspend fun getMessages(sessionId: String): List<ChatMessage> {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = messageFile(sessionId)
                if (!file.exists()) {
                    emptyList()
                } else {
                    runCatching {
                        json.decodeFromString(messageListSerializer, file.readText())
                    }.getOrDefault(emptyList())
                }
            }
        }
    }

    actual suspend fun deleteSessionMessages(sessionId: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                messageFile(sessionId).delete()
            }
        }
    }

    actual suspend fun saveImage(url: String, bytes: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val fileName = "${md5(url)}.jpg"
            val file = File(imagesDir, fileName)
            file.writeBytes(bytes)
            "file://${file.absolutePath}"
        }
    }

    actual suspend fun resolveImage(localPath: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val path = localPath.removePrefix("file://")
            val file = File(path)
            if (file.exists()) file.readBytes() else null
        }
    }

    /** 计算 URL 的 MD5 十六进制摘要，用作图片文件名。 */
    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
