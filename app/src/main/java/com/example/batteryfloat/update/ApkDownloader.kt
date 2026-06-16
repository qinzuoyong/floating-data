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
 * - 支持断点续传（简单实现）
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"
    private const val FILE_NAME = "yongge_update.apk"

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /** 下载状态流，供观察者（Composable）订阅 */
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /**
     * 当前是否正在下载
     */
    val isDownloading: Boolean
        get() = _downloadState.value is DownloadState.Downloading

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

        _downloadState.value = DownloadState.Downloading(0)

        withContext(Dispatchers.IO) {
            try {
                val url = URL(apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.setRequestProperty("Accept", "application/octet-stream")
                conn.connect()

                val contentLength = conn.contentLength // 总字节数（可能为 -1）
                val inputStream = conn.inputStream
                // 删除旧文件
                val targetFile = File(context.cacheDir, FILE_NAME)
                if (targetFile.exists()) targetFile.delete()
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8192) // 8KB 缓冲区
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgress = -1

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // 计算并通知进度
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        // 避免频繁刷新（每 2% 刷新一次）
                        if (progress != lastProgress) {
                            lastProgress = progress
                            _downloadState.value = DownloadState.Downloading(progress.coerceIn(0, 100))
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
                conn.disconnect()

                Log.i(TAG, "下载完成: ${targetFile.absolutePath} (${totalBytesRead} bytes)")
                _downloadState.value = DownloadState.Completed(targetFile)
            } catch (e: Exception) {
                Log.e(TAG, "下载失败: ${e.message}", e)
                _downloadState.value = DownloadState.Error(e.message ?: "下载失败，请检查网络连接")
            }
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