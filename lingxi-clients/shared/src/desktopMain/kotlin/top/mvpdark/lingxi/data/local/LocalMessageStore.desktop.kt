package top.mvpdark.lingxi.data.local

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
 * Desktop 平台 LocalMessageStore：基于用户目录 + JSON 文件。
 *
 * - 消息存储：`~/.lingxi/messages/{sessionId}.json`
 * - 图片存储：`~/.lingxi/images/{md5(url)}.jpg`
 *
 * 文件读写通过 [Mutex] 保证线程安全，IO 操作切换到 [Dispatchers.IO]。
 *
 * @param context Desktop 平台上下文（空占位，未使用；数据目录由 `user.home` 决定）
 */
actual class LocalMessageStore actual constructor(context: PlatformContext) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 消息列表的序列化器，避免反射。 */
    private val messageListSerializer = ListSerializer(ChatMessage.serializer())

    /** 保护同一会话文件读写的互斥锁。 */
    private val mutex = Mutex()

    private val baseDir: File get() = File(System.getProperty("user.home"), ".lingxi")
    private val messagesDir: File get() = File(baseDir, "messages").apply { mkdirs() }
    private val imagesDir: File get() = File(baseDir, "images").apply { mkdirs() }

    private fun messageFile(sessionId: String): File =
        File(messagesDir, "${sanitizeSessionId(sessionId)}.json")

    /**
     * 清洗 sessionId 中的路径不安全字符，防止服务端下发的会话 ID 含
     * `../`、路径分隔符等导致文件被写到 messages 目录之外（路径穿越）。
     */
    private fun sanitizeSessionId(sessionId: String): String {
        val cleaned = sessionId.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return cleaned.ifBlank { "unknown_session" }
    }

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
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val fileName = "${md5(url)}.jpg"
                val file = File(imagesDir, fileName)
                // 原子写：先写临时文件再重命名，避免并发读/写或进程中断留下半截文件。
                // renameTo 在目标已存在时可能失败（Windows），兜底为覆盖拷贝 + 删除临时文件
                val tmpFile = File(imagesDir, "$fileName.tmp")
                tmpFile.writeBytes(bytes)
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
                // 生成合法 file:// URI（处理 Windows 反斜杠，确保 file:/// 前缀）
                "file:///" + file.absolutePath.replace('\\', '/')
            }
        }
    }

    actual suspend fun resolveImage(localPath: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            // 兼容 file:///C:/... （三斜杠）和 file://C:/... （两斜杠）
            val path = localPath.removePrefix("file:///").removePrefix("file://")
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
