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
    /** daemon 启动时打印的端口行,格式 `PORT=<n>` */
    private val PORT_LINE = Regex("""(?:^|\n)\s*PORT=(\d+)""")
    private const val EXEC_TIMEOUT_MS = 15_000L
    private const val PING_TIMEOUT_MS = 2_000L
    private const val ALIVE_CACHE_MS = 5_000L
    /** 单端口连接超时(环回连接是毫秒级) */
    private const val CONNECT_TIMEOUT_MS = 300
    /** 命令响应读超时:socket 层生效,保证阻塞读不会永久挂起 */
    private const val RESPONSE_TIMEOUT_MS = 8_000
    /** shutdown 命令的超时:daemon 收到即 close+exit,正常在毫秒级返回 */
    private const val SHUTDOWN_TIMEOUT_MS = 3_000L

    /** 当前 daemon 的认证令牌(仅进程内持有,重启进程后由下次拉起重置) */
    @Volatile
    private var token: String? = null

    /**
     * 当前 daemon 的监听端口。
     *
     * daemon 用 port=0 让内核随机分配,再把 `PORT=<n>` 打印到 starter 的 stdout,
     * 由 [doStartViaAdb] 解析后写入这里。**不再遍历 41900..41909**——旧实现从固定
     * 端口递增探测且"连上即发令牌",本机任意应用抢 bind 41900 即可在探测第一站
     * 截获令牌(见 bfd_server.c 文件头)。随机端口下探测无从下手。
     */
    @Volatile
    private var daemonPort: Int = 0

    /** 存活探测缓存(TTL 内不重复连 socket;高频采样时避免每拍都探测) */
    @Volatile
    private var aliveCache: Pair<Long, Boolean>? = null

    /**
     * daemon 是否存活(带 [ALIVE_CACHE_MS] 缓存)。
     * 探测是网络 IO,故为 suspend:主线程调用会抛 NetworkOnMainThreadException,
     * 同步/UI 场景请改用 [aliveCached]。
     */
    suspend fun alive(): Boolean {
        if (token == null || daemonPort <= 0) return false
        aliveCache?.let { (at, v) ->
            if (System.currentTimeMillis() - at < ALIVE_CACHE_MS) return v
        }
        var detail = ""
        val v = runCatching {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(PING_TIMEOUT_MS) { execOnce("ping") }
            } == "pong"
        }.getOrElse {
            detail = "${it.javaClass.simpleName}: ${it.message}"
            false
        }
        aliveCache = System.currentTimeMillis() to v
        if (!v && detail.isNotEmpty()) AdbConnectionManager.logDiag("bfd: ping 失败 $detail")
        return v
    }

    /**
     * 缓存态读数(不发起任何探测)：null=尚不可知。
     * 供 UI 做"未知/可用/不可用"三态展示——避免把"还没探测过"误报成"不可用"。
     */
    fun aliveCached(): Boolean? {
        if (token == null || daemonPort <= 0) return false
        return aliveCache?.second
    }

    fun invalidateAlive() {
        aliveCache = null
    }

    /**
     * 让 daemon 自行退出并释放监听端口(用户关闭"高精度数据源"时调用)。
     *
     * daemon 收到 shutdown 会先 close(conn) 再 exit(0)，所以本端读响应会拿到
     * EOF(EOFException) —— 这是预期结果，不是失败，故不走 [exec] 的成功判定。
     * 不作为失败记录：退出后 [alive] 自然转 false。
     */
    suspend fun shutdown() {
        val t = token ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    val sock = connectDaemon()
                    try {
                        val out = DataOutputStream(sock.outputStream)
                        val tk = t.toByteArray(Charsets.UTF_8)
                        out.writeLeInt(tk.size)
                        out.write(tk)
                        val payload = "shutdown".toByteArray(Charsets.UTF_8)
                        out.writeLeInt(payload.size)
                        out.write(payload)
                        out.flush()
                        // 对端 shutdown 时会直接关闭连接，读到 EOF 即视为已送达
                        runCatching { sock.inputStream.read() }
                    } finally {
                        runCatching { sock.close() }
                    }
                }
            }
            token = null
            // 端口一并作废:daemon 已退出,该端口随时可能被其他进程复用,
            // 留着会让后续 alive()/exec() 连到非本 daemon 的监听者
            daemonPort = 0
            invalidateAlive()
        }
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
     * 连接 daemon。
     *
     * 端口来自 daemon 启动时打印的 `PORT=<n>`(随机分配),**不再做端口遍历**:
     * 遍历会让"连上即发令牌"落到抢先占用固定端口的恶意应用手里。
     * 端口未知时直接失败,由上层重新拉起 daemon 获取新端口。
     */
    private fun connectDaemon(): Socket {
        val port = daemonPort
        if (port <= 0) {
            throw java.net.ConnectException("daemon 端口未知(尚未拉起或未解析到 PORT=)")
        }
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            s.soTimeout = RESPONSE_TIMEOUT_MS
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
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
     * 启动后轮询 ping 确认就绪。通道闪断时 exec 自身会等待重连,无需外层守卫。
     * AtomicBoolean 防重入:并发连接各自 launchCarrier 会同时进入本方法,每个都换令牌
     * 并杀旧实例,与在途 ping/exec 竞态(实测 diag 日志"尝试1/2/3"跨线程交错);
     * 与 ShizukuChannel.starting 同款守卫,后到者直接返回由先到者完成拉起。
     */
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun startViaAdb(context: Context): Boolean {
        if (!starting.compareAndSet(false, true)) return false
        try {
            return doStartViaAdb(context)
        } finally {
            starting.set(false)
        }
    }

    private suspend fun doStartViaAdb(context: Context): Boolean {
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
        val newToken = newToken()
        // 端口必须随令牌一起重置:旧端口属于旧 daemon,新 daemon 会重新随机分配
        token = newToken
        daemonPort = 0
        invalidateAlive()
        var dispatched = false
        for (n in 1..3) {
            val out = AdbConnectionManager.exec(
                "${starter.absolutePath} --token=$newToken"
            )
            AdbConnectionManager.logDiag(ctx, "bfd: starter 尝试$n 输出=${out?.take(200) ?: "null"}")
            if (out != null) {
                // daemon 在 fork 前把 `PORT=<n>` 打到 stdout,starter 的整段输出经 shell 回传。
                // 解析不到端口就不能用——宁可按失败处理让上层重试,也不回退到固定端口遍历
                // (那正是令牌被截获的原因)。
                val port = parsePort(out)
                if (port > 0) {
                    daemonPort = port
                    dispatched = true
                    break
                }
                Log.w(TAG, "starter 输出未包含 PORT=,重试")
            }
            kotlinx.coroutines.delay(2_000)
        }
        if (!dispatched) {
            Log.w(TAG, "starter 执行失败或未取到端口(通道不可用)")
            return false
        }
        repeat(10) {
            invalidateAlive()
            if (alive()) {
                Log.i(TAG, "内置特权服务已就绪(第 ${it + 1} 秒,端口 $daemonPort)")
                AdbConnectionManager.logDiag(
                    ctx, "bfd: 内置特权服务就绪(第 ${it + 1} 秒,端口 $daemonPort)"
                )
                return true
            }
            kotlinx.coroutines.delay(1_000)
        }
        Log.w(TAG, "拉起后 ping 未就绪")
        AdbConnectionManager.logDiag(ctx, "bfd: 拉起后 ping 未就绪")
        return false
    }

    /** 从 starter 输出里取 `PORT=<n>`;取不到返回 0 */
    private fun parsePort(output: String): Int =
        PORT_LINE.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 1..65535 } ?: 0

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
