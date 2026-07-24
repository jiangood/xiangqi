package io.github.jiangood.xq.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.jiangood.xq.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val publishedAt: String,
    val assetSize: Long
)

object UpdateManager {

    private const val GITHUB_API = "https://api.github.com/repos/jiangood/xiangqi/releases/latest"
    private const val APK_SUFFIX = "-thin.apk"
    private const val TIMEOUT_MS = 15000

    /**
     * 检查 GitHub 上是否有新版本。
     * @param currentVersion 当前版本号, 如 "9.3.2"
     * @return 有新版本时返回 UpdateInfo, 否则 null
     */
    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                    // User-Agent required by GitHub API
                    setRequestProperty("User-Agent", "XiangqiApp")
                }

                val code = conn.responseCode
                if (code != 200) {
                    val msg = "GitHub API 返回 $code"
                    AppLog.add("[更新] $msg")
                    return@withContext Result.failure(Exception(msg))
                }

                val json = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val release = JSONObject(json)
                val tagName = release.optString("tag_name", "").removePrefix("v")
                val body = release.optString("body", "")
                val publishedAt = release.optString("published_at", "")
                val assets = release.optJSONArray("assets")

                if (tagName.isEmpty()) {
                    return@withContext Result.failure(Exception("未找到版本信息"))
                }

                // 比较版本
                if (compareVersions(tagName, currentVersion) <= 0) {
                    AppLog.add("[更新] 已是最新版本 $currentVersion")
                    return@withContext Result.success(null)
                }

                // 查找 thin APK
                var downloadUrl: String? = null
                var assetSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(APK_SUFFIX)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            assetSize = asset.optLong("size", 0)
                            break
                        }
                    }
                }

                if (downloadUrl.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("未找到 APK 下载地址"))
                }

                AppLog.add("[更新] 发现新版本 v$tagName → $downloadUrl")
                Result.success(
                    UpdateInfo(
                        latestVersion = tagName,
                        downloadUrl = downloadUrl,
                        releaseNotes = body,
                        publishedAt = publishedAt,
                        assetSize = assetSize
                    )
                )
            } catch (e: Exception) {
                AppLog.add("[更新] 检查失败: ${e.message}")
                Result.failure(e)
            }
        }

    /**
     * 下载 APK 到缓存目录，通过 progress 回调报告进度 (0f ~ 1f)
     * 每次都会重新下载，不会使用之前残留的缓存。
     * @return 下载成功后的 File（已验证完整性），失败返回 null
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        version: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val targetFile = File(context.cacheDir, "xq-$version$APK_SUFFIX")
        try {
            // 删除之前残留的缓存文件，避免使用损坏的 APK
            if (targetFile.exists()) {
                AppLog.add("[更新] 删除旧缓存: ${targetFile.name}")
                targetFile.delete()
            }

            AppLog.add("[更新] 开始下载 $url")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = 30000
                setRequestProperty("User-Agent", "XiangqiApp")
                // GitHub asset URLs redirect to CDN
                instanceFollowRedirects = true
            }

            val totalBytes = conn.contentLengthLong
            val input = conn.inputStream
            FileOutputStream(targetFile).use { out ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                var lastReported = -1f
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        val pct = (totalRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                        // 每 5% 回传一次，避免过度刷新 UI
                        if (pct - lastReported >= 0.05f) {
                            onProgress(pct)
                            lastReported = pct
                        }
                    }
                }
            }
            input.close()
            conn.disconnect()

            onProgress(1f)
            AppLog.add("[更新] 下载完成: ${targetFile.absolutePath}")

            // 验证 APK 完整性：检查 ZIP 文件头魔数
            if (!verifyApk(targetFile)) {
                targetFile.delete()
                AppLog.add("[更新] APK 完整性校验失败，已删除")
                return@withContext null
            }

            AppLog.add("[更新] APK 完整性校验通过")
            targetFile
        } catch (e: Exception) {
            AppLog.add("[更新] 下载失败: ${e.message}")
            // 下载失败时清理不完整的文件
            try { if (targetFile.exists()) targetFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * 调用系统安装器安装 APK
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.add("[更新] 启动安装器")
        } catch (e: Exception) {
            AppLog.add("[更新] 启动安装失败: ${e.message}")
        }
    }

    /**
     * 验证 APK 文件的完整性:
     * 1. 文件存在且大于 1MB（APK 最小合理大小）
     * 2. ZIP 文件头魔数 (PK\x03\x04)
     */
    private fun verifyApk(file: File): Boolean {
        if (!file.exists() || file.length() < 1024 * 1024) {
            AppLog.add("[更新] APK 文件大小异常: ${file.length()} bytes")
            return false
        }
        return try {
            val magic = ByteArray(4)
            val bytesRead = file.inputStream().use { it.read(magic) }
            val valid = bytesRead == 4 &&
                    magic[0] == 0x50.toByte() &&  // P
                    magic[1] == 0x4B.toByte() &&  // K
                    magic[2] == 0x03.toByte() &&  // \x03
                    magic[3] == 0x04.toByte()     // \x04
            if (!valid) {
                AppLog.add("[更新] APK 文件头异常，不是有效的 ZIP/APK 文件")
            }
            valid
        } catch (e: Exception) {
            AppLog.add("[更新] APK 校验异常: ${e.message}")
            false
        }
    }

    /**
     * 语义化版本比较: >0 表示 v1 更新, 0 相等, <0 表示 v2 更新
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }
}
