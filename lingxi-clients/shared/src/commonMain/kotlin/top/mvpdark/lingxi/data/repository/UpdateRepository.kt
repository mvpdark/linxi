package top.mvpdark.lingxi.data.repository

import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import top.mvpdark.lingxi.core.network.ApiClient
import top.mvpdark.lingxi.core.util.runCatchingCancellable

/**
 * GitHub Release 信息（仅提取需要的字段）。
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

/**
 * 版本检查结果。
 */
data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val needsUpdate: Boolean,
)

/**
 * 应用更新仓库：检查 GitHub Releases 最新版本。
 *
 * 调用 GitHub API: GET /repos/{owner}/{repo}/releases/latest
 * 从 tag_name 提取版本号，从 assets 找到 APK 下载链接。
 */
class UpdateRepository(
    private val apiClient: ApiClient,
) {
    companion object {
        private const val GITHUB_API = "https://api.github.com"
        private const val REPO = "mvpdark/linxi"
    }

    /**
     * 检查最新版本。
     *
     * @param currentVersion 当前版本号（如 "1.0.35"）。
     * @return 更新信息，或 null 表示检查失败。
     */
    suspend fun checkLatestVersion(currentVersion: String): UpdateInfo? {
        return runCatchingCancellable {
            val release: GitHubRelease = apiClient.httpClient.get(
                "$GITHUB_API/repos/$REPO/releases/latest",
            ).body()

            // 从 tag_name 提取版本号（如 "v1.0.35" -> "1.0.35"）
            val latestVersion = release.tagName.removePrefix("v")

            // 查找 APK 资产
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            val downloadUrl = apkAsset?.browserDownloadUrl ?: ""

            // 比较版本号
            val needsUpdate = compareVersions(latestVersion, currentVersion) > 0

            UpdateInfo(
                latestVersion = latestVersion,
                downloadUrl = downloadUrl,
                releaseNotes = release.body.ifBlank { release.name },
                needsUpdate = needsUpdate,
            )
        }.getOrNull()
    }

    /**
     * 比较语义化版本号。
     *
     * @return 正数表示 v1 > v2，负数表示 v1 < v2，0 表示相等。
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }
}
