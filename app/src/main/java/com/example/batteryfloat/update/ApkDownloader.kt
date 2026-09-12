package com.example.batteryfloat.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
 * - 保存到缓存子目录 apk/（FileProvider 仅暴露该子目录，不再暴露整个 cache）
 * - 校验下载文件有效性（ZIP magic bytes）
 * - 逐跳校验跳转域名 + 比对签名证书（防中间人替换安装包）
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"
    private const val FILE_NAME = "yongge_update.apk"

    /** APK 存放子目录（与 res/xml/file_paths.xml 的 apk/ 路径严格对应） */
    private const val APK_DIR = "apk"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 允许的最大跳转次数（Gitee/GitHub → CDN 通常 1~2 跳） */
    private const val MAX_REDIRECTS = 5

    /**
     * 允许的 APK 下载域（自家发布渠道）。
     *
     * 匹配规则为"精确相等 或 .后缀"，故各发布渠道的官方子域已自动覆盖：
     * Gitee 附件 CDN foruda.gitee.com（实测跳转链末跳）、GitHub Asset CDN
     * objects.githubusercontent.com 等均在列；同时 evil-gitee.com、
     * gitee.com.evil.com 这类前后缀伪造不会命中。
     */
    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "gitee.com",
        "github.com",
        "objects.githubusercontent.com"
    )

    private val DOWNLOAD_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /** 下载状态流，供观察者（Composable）订阅 */
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** 当前是否正在下载 */
    val isDownloading: Boolean
        get() = _downloadState.value is DownloadState.Downloading

    /** 下载文件句柄（缓存子目录 apk/，与 FileProvider 暴露路径一致） */
    private fun apkFile(context: Context): File {
        val dir = File(context.cacheDir, APK_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    /** 校验下载来源：仅允许自家发布域 + https，防异常更新源注入任意 URL */
    private fun isAllowedDownloadUrl(apkUrl: String): Boolean = try {
        val u = URL(apkUrl)
        u.protocol.equals("https", true) &&
            ALLOWED_DOWNLOAD_HOSTS.any { u.host == it || u.host.endsWith(".$it") }
    } catch (e: Exception) {
        false
    }

    /**
     * 打开连接并逐跳校验域名白名单。
     *
     * HttpURLConnection 默认自动跟随 302，会使"只校验首跳"形同虚设（可跨域跳到任意主机），
     * 故关闭自动跳转、手动逐跳校验后再继续。
     */
    private fun openValidatedConnection(startUrl: String): HttpURLConnection {
        var current = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", DOWNLOAD_UA)
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            if (code in 300..399 && !location.isNullOrBlank()) {
                conn.disconnect()
                val next = URL(URL(current), location).toString()
                if (!isAllowedDownloadUrl(next)) {
                    throw IOException("跳转目标不在允许的发布域")
                }
                current = next
                continue
            }
            return conn
        }
        throw IOException("重定向次数超过上限（$MAX_REDIRECTS）")
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
                conn = openValidatedConnection(apkUrl)

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
                val targetFile = apkFile(context)
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

                // ① 校验下载文件是否为有效的 APK（ZIP 格式，magic bytes = PK）
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

                // ② 校验签名证书与已安装版本一致：防中间人/供应链替换
                //    （仅比签名集合，不依赖远端元数据，可离线判定）
                if (!hasSameSignatureAsInstalled(context, targetFile)) {
                    Log.e(TAG, "签名校验失败：下载包签名与已安装版本不一致，已拒绝")
                    targetFile.delete()
                    _downloadState.value = DownloadState.Error(
                        "安装包签名校验失败，已阻止安装（可能被篡改）"
                    )
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

    // ===== 签名校验 =====

    /**
     * 下载包与已安装版本的签名证书集合是否一致。
     * 任一环节取不到签名（解析失败/无签名/被替换）都返回 false，即拒绝安装。
     */
    private fun hasSameSignatureAsInstalled(context: Context, apkFile: File): Boolean {
        val installed = installedSignatureHashes(context)
        val downloaded = archiveSignatureHashes(context, apkFile)
        if (installed.isEmpty() || downloaded.isEmpty()) {
            Log.w(TAG, "签名集合为空: installed=${installed.size} downloaded=${downloaded.size}")
            return false
        }
        return installed == downloaded
    }

    private fun installedSignatureHashes(context: Context): Set<String> = try {
        val pm = context.packageManager
        signatureHashes(
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        )
    } catch (e: Exception) {
        Log.w(TAG, "读取已安装签名失败: ${e.message}")
        emptySet()
    }

    private fun archiveSignatureHashes(context: Context, apkFile: File): Set<String> = try {
        val pm = context.packageManager
        signatureHashes(
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        )
    } catch (e: Exception) {
        Log.w(TAG, "读取下载包签名失败: ${e.message}")
        emptySet()
    }

    /** 取签名证书的 SHA-256 十六进制集合（minSdk 34 恒定走 signingInfo 分支） */
    private fun signatureHashes(info: android.content.pm.PackageInfo?): Set<String> {
        val signers = info?.signingInfo?.apkContentsSigners ?: return emptySet()
        return signers.map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

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
        val file = apkFile(context)
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
