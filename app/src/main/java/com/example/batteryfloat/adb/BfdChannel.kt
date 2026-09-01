package com.example.batteryfloat.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * 内置特权服务(libbfd.so)客户端(方案三:自包含载体)
 *
 * libbfd.so 由 ADB 通道以 shell 身份拉起一次后守护化(脱离 adbd 存活),
 * 监听 127.0.0.1 TCP([PORT] 起,占用时顺延),代本应用执行 shell 命令。
 *
 * 安全边界:仅绑定环回;每个连接首包携带随机令牌(--token 传给 daemon,
 * Android hidepid 下其他应用读不到该进程 cmdline),不匹配即拒绝。
 *
 * 协议:4字节小端令牌长 + 令牌 + 4字节小端命令长 + 命令 → 4字节小端输出长 + 输出
 */
object BfdChannel {

    private const val TAG = "BfdChannel"
    private const val BASE_PORT = 41900
    /** daemon 端口顺延范围(与 bfd_server.c 的 bind 重试次数一致) */
    private const val PORT_SPAN = 10
    private const val EXEC_TIMEOUT_MS = 15_000L
    private const val PING_TIMEOUT_MS = 2_000L
    private const val ALIVE_CACHE_MS = 5_000L

    /** 当前 daemon 的认证令牌(仅进程内持有,重启进程后由下次拉起重置) */
    @Volatile
    private var token: String? = null

    /** 存活探测缓存(TTL 内不重复连 socket;高频采样时避免每拍都探测) */
    @Volatile
    private var aliveCache: Pair<Long, Boolean>? = null

    /**
     * daemon 是否存活(带 5s 缓存);ping 失败即视为死亡,下次重新探测。
     * 主线程(UI)只读缓存不做探测——探测是网络 IO,主线程会抛
     * NetworkOnMainThreadException;缓存由后台调用(canExec/采样/拉起)填充
     */
    fun alive(): Boolean {
        if (token == null) return false
        aliveCache?.let { (at, v) ->
            if (System.currentTimeMillis() - at < ALIVE_CACHE_MS) return v
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return aliveCache?.second ?: false
        }
        var detail = ""
        val v = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(PING_TIMEOUT_MS) { execOnce("ping") }
            } == "pong"
        }.getOrElse {
            detail = "${it.javaClass.simpleName}: ${it.message}"
            false
        }
        aliveCache = System.currentTimeMillis() to v
        if (!v && detail.isNotEmpty()) AdbConnectionManager.logDiag("bfd: ping 失败 $detail")
        return v
    }

    fun invalidateAlive() {
        aliveCache = null
    }

    /**
     * 经 daemon 执行命令
     * @return 输出;daemon 不在/超时/出错返回 null
     */
    suspend fun exec(command: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { withTimeoutOrNull(EXEC_TIMEOUT_MS) { execOnce(command) } }
                .onFailure { Log.w(TAG, "daemon 执行失败: ${it.message}"); invalidateAlive() }
                .getOrNull()
        }

    /** 阻塞实现；超时由调用方协程限制(daemon 侧另有 15s 命令超时兜底) */
    private fun execOnce(command: String): String {
        val t = token ?: error("daemon 未拉起")
        val sock = connectDaemon()
        try {
            val out = DataOutputStream(sock.outputStream)
            val input = DataInputStream(sock.inputStream)
            // 帧长为小端(与 daemon 的原生 uint 一致;DataOutputStream.writeInt 是大端,勿用)
            val tk = t.toByteArray(Charsets.UTF_8)
            out.writeLeInt(tk.size)
            out.write(tk)
            val payload = command.toByteArray(Charsets.UTF_8)
            out.writeLeInt(payload.size)
            out.write(payload)
            out.flush()
            val len = input.readLeInt()
            if (len > 4 * 1024 * 1024) error("响应长度异常: $len")
            val buf = ByteArray(len)
            input.readFully(buf)
            return String(buf, Charsets.UTF_8)
        } catch (e: Exception) {
            // 诊断:区分连接被拒/重置/读超时
            Log.w(TAG, "daemon 连接失败: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun DataOutputStream.writeLeInt(v: Int) {
        write(v)
        write(v ushr 8)
        write(v ushr 16)
        write(v ushr 24)
    }

    /**
     * 连接 daemon：daemon 启动时若默认端口被占用会向上顺延监听
     * (bfd_server.c 内 BASE_PORT..BASE_PORT+PORT_SPAN-1)，客户端同范围逐个尝试。
     */
    private fun connectDaemon(): Socket {
        var last: Exception? = null
        for (p in BASE_PORT until BASE_PORT + PORT_SPAN) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", p), 2_000)
                return s
            } catch (e: Exception) {
                last = e
                runCatching { s.close() }
            }
        }
        throw last ?: java.net.ConnectException("daemon 端口 ${BASE_PORT}-${BASE_PORT + PORT_SPAN - 1} 无监听")
    }

    private fun DataInputStream.readLeInt(): Int {
        val b = ByteArray(4)
        readFully(b)
        return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
    }

    /**
     * 借 ADB 通道拉起 daemon(shell 域执行我们的 starter;幂等,失败返回 false)。
     * 启动后轮询 ping 确认就绪。通道闪断时 exec 自身会等待重连,无需外层守卫
     */
    suspend fun startViaAdb(context: Context): Boolean {
        val ctx = context.applicationContext
        val starter = File(ctx.applicationInfo.nativeLibraryDir, "libbfd.so")
        AdbConnectionManager.logDiag(
            ctx, "bfd: startViaAdb starter=${starter.exists()} path=${starter.absolutePath}"
        )
        if (!starter.exists()) {
            Log.w(TAG, "starter 不存在: ${starter.absolutePath}")
            return false
        }
        // 每次拉起生成新令牌;旧 daemon 会被 starter 按进程名清掉
        token = newToken()
        var dispatched = false
        for (n in 1..3) {
            val out = AdbConnectionManager.exec(
                "${starter.absolutePath} --token=$token"
            )
            AdbConnectionManager.logDiag(ctx, "bfd: starter 尝试$n 输出=${out?.take(120) ?: "null"}")
            if (out != null) {
                dispatched = true
                break
            }
            kotlinx.coroutines.delay(2_000)
        }
        if (!dispatched) {
            Log.w(TAG, "starter 执行失败(通道不可用)")
            return false
        }
        repeat(10) {
            invalidateAlive()
            if (alive()) {
                Log.i(TAG, "内置特权服务已就绪(第 ${it + 1} 秒)")
                AdbConnectionManager.logDiag(ctx, "bfd: 内置特权服务就绪(第 ${it + 1} 秒)")
                return true
            }
            kotlinx.coroutines.delay(1_000)
        }
        Log.w(TAG, "拉起后 ping 未就绪")
        AdbConnectionManager.logDiag(ctx, "bfd: 拉起后 ping 未就绪")
        return false
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
