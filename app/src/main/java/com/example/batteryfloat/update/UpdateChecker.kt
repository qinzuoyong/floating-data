package com.example.batteryfloat.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新信息数据类
 * @param hasUpdate 是否有新版本
 * @param latestVersion 最新版本号（不含 v 前缀）
 * @param downloadUrl 下载页面 URL
 * @param apkDownloadUrl APK 文件直链下载地址
 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val downloadUrl: String,
    val apkDownloadUrl: String = ""
)

/**
 * 版本更新检查工具
 * 双源检测：优先 Gitee Open API，失败自动切换 GitHub API
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITEE_API = "https://gitee.com/api/v5/repos/qinzuoyong/floating-data/releases/latest"
    private const val GITHUB_API = "https://api.github.com/repos/qinzuoyong/floating-data/releases/latest"

    /**
     * 检查是否有新版本
     * 双源：Gitee → GitHub（失败自动切换）
     * @param currentVersion 当前应用版本号（如 "1.60"）
     * @return UpdateInfo 更新信息
     */
    suspend fun check(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        // 先尝试 Gitee
        val giteeResult = tryFetchRelease(GITEE_API, currentVersion)
        if (giteeResult != null) {
            return@withContext giteeResult
        }
        Log.i(TAG, "Gitee 检测失败，切换到 GitHub API")
        // Gitee 失败，切换 GitHub
        val githubResult = tryFetchRelease(GITHUB_API, currentVersion)
        if (githubResult != null) {
            return@withContext githubResult
        }
        // 都失败
        Log.w(TAG, "所有更新源均检测失败")
        UpdateInfo(false, "", "")
    }

    // Gitee 仓库信息
    private const val GITEE_OWNER = "qinzuoyong"
    private const val GITEE_REPO = "floating-data"
    // GitHub 仓库信息
    private const val GITHUB_OWNER = "qinzuoyong"
    private const val GITHUB_REPO = "floating-data"

    /**
     * 尝试从指定 API 获取最新 Release 信息
     * @param apiUrl API 地址
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param currentVersion 当前版本号
     * @return UpdateInfo 或 null（失败时）
     */
    private fun tryFetchRelease(apiUrl: String, currentVersion: String): UpdateInfo? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(apiUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            // GitHub API 需要 User-Agent
            conn.setRequestProperty("User-Agent", "BatteryFloating/1.60")

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val latestTag = json.getString("tag_name").removePrefix("v")

            // 确定下载页面 URL
            val isGitee = apiUrl.contains("gitee.com")
            val downloadUrl = if (isGitee) {
                "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/releases"
            } else {
                "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
            }

            // 从 API 响应中提取 APK 附件下载地址
            var apkUrl = ""

            // Gitee API 用 attachments 字段
            if (isGitee) {
                if (json.has("attachments")) {
                    val assets = json.getJSONArray("attachments")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                // 也检查 assets 字段
                if (apkUrl.isBlank() && json.has("assets")) {
                    val assets = json.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                // 兜底：拼接 Gitee 下载地址
                if (apkUrl.isBlank()) {
                    apkUrl = "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/releases/download/v${latestTag}/yongge.apk"
                }
            } else {
                // GitHub API 用 assets 字段
                if (json.has("assets")) {
                    val assets = json.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                // 兜底：拼接 GitHub 下载地址
                if (apkUrl.isBlank()) {
                    apkUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v${latestTag}/yongge.apk"
                }
            }

            val hasUpdate = compareVersions(latestTag, currentVersion) > 0
            Log.i(TAG, "源: ${if (isGitee) "Gitee" else "GitHub"}, 当前: $currentVersion, 最新: $latestTag, 有更新: $hasUpdate, APK: $apkUrl")
            UpdateInfo(hasUpdate, latestTag, downloadUrl, apkUrl)
        } catch (e: Exception) {
            Log.w(TAG, "从 ${apiUrl} 获取更新信息失败: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 比较两个语义化版本号
     * @return >0 表示 v1 > v2, <0 表示 v1 < v2, =0 表示相等
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val d1 = v1.toDoubleOrNull() ?: 0.0
        val d2 = v2.toDoubleOrNull() ?: 0.0
        return d1.compareTo(d2)
    }
}