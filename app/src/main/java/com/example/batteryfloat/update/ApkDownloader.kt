package com.example.batteryfloat.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 下载状态
 */
sealed class DownloadState {
    /** 空闲/未开始 */
    data object Idle : DownloadState()
    /** 下载中，progress 0~100 */
    data class Downloading(val progress: Int) : DownloadState()
    /** 下载完成，file 为下载的 APK 文件 */
    data class Completed(val file: File) : DownloadState()
    /** 下载失败 */
    data class Error(val message: String) : DownloadState()
}

/**
 * APK 下载器
 * - 支持下载进度通知（StateFlow）
 * - 自动保存到应用缓存目录
 * - 下载后校验文件有效性（ZIP magic bytes）
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"
    private const val FILE_NAME = "yongge_update.apk"

    /** 允许的 APK 下载域（自家发布渠道；跟随 302 到 CDN 子域属正常） */
    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "gitee.com",
        "github.com",
        "objects.githubusercontent.com"
    )

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /** 下载状态流，供观察者（Composable）订阅 */
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** 当前是否正在下载 */
    val isDownloading: Boolean
        get() = _downloadState.value is DownloadState.Downloading

    /** 校验下载来源：仅允许自家发布域 + https，防异常更新源注入任意 URL */
    private fun isAllowedDownloadUrl(apkUrl: String): Boolean = try {
        val u = URL(apkUrl)
        u.protocol.equals("https", true) &&
            ALLOWED_DOWNLOAD_HOSTS.any { u.host == it || u.host.endsWith(".$it") }
    } catch (e: Exception) {
        false
    }

    /**
     * 开始下载 APK
     * @param context 上下文（用于获取缓存目录）
     * @param apkUrl APK 下载地址
     */
    suspend fun download(context: Context, apkUrl: String) {
        // 如果已经在下载中，忽略重复请求
        if (_downloadState.value is DownloadState.Downloading) {
            Log.w(TAG, "下载已在进行中，忽略重复请求")
            return
        }

        if (!isAllowedDownloadUrl(apkUrl)) {
            Log.e(TAG, "下载地址不在允许的发布域内，拒绝下载")
            _downloadState.value = DownloadState.Error("下载地址不受信任")
            return
        }

        _downloadState.value = DownloadState.Downloading(0)

        withContext(Dispatchers.IO) {
            var inputStream: java.io.InputStream? = null
            var outputStream: FileOutputStream? = null
            var conn: HttpURLConnection? = null
            try {
                val url = URL(apkUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                // 设置 User-Agent 防止 Gitee CDN 返回 HTML 错误页面
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                conn.connect()

                // 检查 HTTP 响应码，非 2xx 视为失败（防止下载错误页面当 APK）
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    Log.e(TAG, "服务器返回错误码: $responseCode")
                    _downloadState.value = DownloadState.Error(
                        "下载失败，服务器返回错误码: $responseCode"
                    )
                    return@withContext
                }

                val contentLength = conn.contentLength
                inputStream = conn.inputStream

                // 删除旧文件
                val targetFile = File(context.cacheDir, FILE_NAME)
                if (targetFile.exists()) targetFile.delete()
                outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgress = -1

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // 计算并通知进度
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            _downloadState.value =
                                DownloadState.Downloading(progress.coerceIn(0, 100))
                        }
                    } else {
                        // 服务器未返回 Content-Length（chunked 编码）时按已下载字节滚动进度，
                        // 每 50KB 进 1%，封顶 99%，避免全程卡 0%；完成由 Completed 状态接管
                        val pseudoProgress = (1 + (totalBytesRead / (50 * 1024L)).toInt()).coerceIn(1, 99)
                        if (pseudoProgress != lastProgress) {
                            lastProgress = pseudoProgress
                            _downloadState.value = DownloadState.Downloading(pseudoProgress)
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                outputStream = null
                inputStream.close()
                inputStream = null
                conn.disconnect()
                conn = null

                // 校验下载文件是否为有效的 APK（ZIP 格式，magic bytes = PK）
                if (!isValidApkFile(targetFile)) {
                    Log.e(TAG, "下载的文件不是有效的 APK: ${targetFile.length()} bytes")
                    // 读取文件头诊断
                    val headerBuf = ByteArray(64)
                    targetFile.inputStream().use { it.read(headerBuf) }
                    val headerStr = String(headerBuf, Charsets.UTF_8).take(64)
                    Log.e(TAG, "文件头: $headerStr")
                    targetFile.delete()
                    _downloadState.value =
                        DownloadState.Error("下载的文件不是有效的 APK 安装包，请检查网络后重试")
                    return@withContext
                }

                Log.i(TAG, "下载完成: ${totalBytesRead} bytes")
                _downloadState.value = DownloadState.Completed(targetFile)
            } catch (e: Exception) {
                Log.e(TAG, "下载失败: ${e.message}", e)
                _downloadState.value =
                    DownloadState.Error(e.message ?: "下载失败，请检查网络连接")
            } finally {
                outputStream?.tryClose()
                inputStream?.tryClose()
                conn?.disconnect()
            }
        }
    }

    /** 安全关闭流 */
    private fun java.io.Closeable?.tryClose() {
        try { this?.close() } catch (_: Exception) {}
    }

    /**
     * 校验文件是否为有效的 APK（ZIP 格式）
     * APK 本质是 ZIP 文件，文件头前 2 字节应为 0x50 0x4B ("PK")
     */
    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val buffer = ByteArray(4)
            file.inputStream().use { input ->
                val bytesRead = input.read(buffer)
                // 至少读取 2 字节才能校验 ZIP magic bytes
                if (bytesRead < 2) return false
                // ZIP magic: 0x50 0x4B 0x03 0x04
                buffer[0] == 0x50.toByte() && buffer[1] == 0x4B.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 重置下载状态为空闲
     */
    fun reset() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * 清理已下载的文件
     */
    fun cleanup(context: Context) {
        val file = File(context.cacheDir, FILE_NAME)
        if (file.exists()) file.delete()
        reset()
    }

    /**
     * 启动系统安装器安装下载的 APK
     * @param context 上下文（Activity context）
     * @param file 下载完成的 APK 文件
     * @return true 表示安装 Intent 已发出
     */
    fun install(context: Context, file: File): Boolean {
        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动安装器失败: ${e.message}", e)
            false
        }
    }
}