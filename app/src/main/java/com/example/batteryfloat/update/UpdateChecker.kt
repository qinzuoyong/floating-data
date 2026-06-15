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
 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val downloadUrl: String
)

/**
 * 版本更新检查工具
 * 调用 Gitee Open API 获取最新 Release 版本，与当前版本比较
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val API_URL = "https://gitee.com/api/v5/repos/qinzuoyong/floating-data/releases/latest"

    /**
     * 检查是否有新版本
     * @param currentVersion 当前应用版本号（如 "1.41"）
     * @return UpdateInfo 更新信息
     */
    suspend fun check(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")

            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val latestTag = json.getString("tag_name").removePrefix("v")
            val downloadUrl = "https://gitee.com/qinzuoyong/floating-data/releases"

            val hasUpdate = compareVersions(latestTag, currentVersion) > 0
            Log.i(TAG, "当前版本: $currentVersion, 最新版本: $latestTag, 有更新: $hasUpdate")
            UpdateInfo(hasUpdate, latestTag, downloadUrl)
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败: ${e.message}")
            UpdateInfo(false, "", "")
        }
    }

    /**
     * 比较两个语义化版本号
     * @return >0 表示 v1 > v2, <0 表示 v1 < v2, =0 表示相等
     */
    private fun compareVersions(v1: String, v2: String): Int {
        // 使用十进制浮点数比较（1.5 > 1.44）
        val d1 = v1.toDoubleOrNull() ?: 0.0
        val d2 = v2.toDoubleOrNull() ?: 0.0
        return d1.compareTo(d2)
    }
}
