package com.example.batteryfloat.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.batteryfloat.PrefsKeys
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.net.ssl.SSLException

/** 特权通道状态 */
enum class AdbState {
    /** 从未配对成功(或系统低于 Android 11) */
    NOT_PAIRED,
    /** NSD 发现连接端口中 */
    DISCOVERING,
    /** 建立 ADB 连接中 */
    CONNECTING,
    /** 已连接,shell 流可用 */
    CONNECTED,
    /** 已配对但当前断开,自动重连中 */
    DISCONNECTED,
    /** 配对流程进行中 */
    PAIRING,
    /** 已配对但设备不再信任本机密钥(TLS 握手失败),需重新配对 */
    AUTH_FAILED
}

/**
 * ADB 无线调试特权通道管理器(单例,移植协议栈的编排层)
 *
 * - 持有 AdbKey(本机加密存储)与 AdbClient 常驻连接
 * - NSD 发现端口:开机/重启后端口漫游,免配对自动重连
 * - 对外提供 [exec]:特权 shell 命令,失败返回 null 由消费方降级(批次 3 接电池采样)
 * - 断线指数退避重连(3s 起,上限 60 秒);[exec] 调用会立即触发一次连接尝试;
 *   亮屏/解锁事件也触发立即重连(拿起手机就能看到连回,事件驱动零轮询)
 */
object AdbConnectionManager {

    private const val TAG = "AdbConnManager"
    private const val ADB_PREFS_NAME = "adb_prefs"
    private const val KEY_NAME = "batteryfloat@local"
    /** 是否配对成功过(设备侧已信任本机公钥);设备撤销信任(握手失败)时清除 */
    private const val PREF_PAIRED = "adb_paired"

    private const val DISCOVER_TIMEOUT_MS = 12_000L
    private const val EXEC_TIMEOUT_MS = 4_000L
    private const val RECONNECT_MIN_MS = 3_000L
    /** 退避上限 60 秒:上限过高会让"重新打开无线调试后迟迟不重连"(15 分钟上线的实测教训) */
    private const val RECONNECT_MAX_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    private var appContext: Context? = null
    private var keyStore: SharedPreferences? = null

    @Volatile
    private var key: AdbKey? = null

    @Volatile
    private var client: AdbClient? = null

    @Volatile
    private var enabled = false

    private var reconnectJob: Job? = null

    /** 当前重连退避间隔(重连循环与亮屏触发器共用,亮屏时重置) */
    @Volatile
    private var backoffMs = RECONNECT_MIN_MS

    private var unlockReceiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow(AdbState.NOT_PAIRED)
    val state: StateFlow<AdbState> = _state

    /** 最近一次 shell 命令成功的时间戳(0=从未成功);断连态 UI 展示通道新鲜度用 */
    @Volatile
    var lastSuccessAt: Long = 0L
        private set

    /** 最近一次连接失败的归类描述(发现超时/对端关闭/读超时/端口拒绝等),诊断与 UI 用 */
    @Volatile
    var lastFailure: String? = null
        private set

    val isReady: Boolean get() = _state.value == AdbState.CONNECTED

    /** 最近成功距今的可读描述("刚刚"/"x 分钟前"/"x 小时前");从未成功返回 null */
    fun lastSuccessAgoText(): String? {
        val t = lastSuccessAt
        if (t <= 0) return null
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 60 -> "刚刚"
            s < 3600 -> "${s / 60} 分钟前"
            else -> "${s / 3600} 小时前"
        }
    }

    /** 进程内幂等初始化(MainActivity onCreate 调用);已启用则启动自动重连 */
    fun setup(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
            keyStore = appContext!!.getSharedPreferences(ADB_PREFS_NAME, Context.MODE_PRIVATE)
            enabled = appContext!!.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PrefsKeys.ADB_PRIV_ENABLED, false)
            PrivShell.init(appContext!!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                key = try {
                    AdbKey(PreferenceAdbKeyStore(keyStore!!), KEY_NAME)
                } catch (e: Throwable) {
                    Log.e(TAG, "AdbKey 初始化失败", e)
                    null
                }
                _state.value = if (key != null) AdbState.DISCONNECTED else AdbState.NOT_PAIRED
                registerUnlockTrigger(appContext!!)
                if (enabled && key != null) startReconnectLoop()
            }
        }
    }

    /** UI 开关;开启后立即尝试连接,关闭仅停用(密钥保留,下次开启免重配) */
    fun setEnabled(context: Context, value: Boolean) {
        setup(context)
        enabled = value
        context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PrefsKeys.ADB_PRIV_ENABLED, value).apply()
        if (value && key != null) {
            startReconnectLoop()
            scope.launch { connectOnceInternal() }
        } else if (!value) {
            reconnectJob?.cancel()
            reconnectJob = null
            closeClientQuietly()
            _state.value = if (key != null) AdbState.DISCONNECTED else AdbState.NOT_PAIRED
        }
    }

    /**
     * 启动通知栏配对流程(配对对话框调用)。
     * 编排见 AdbPairingService:先持续 NSD 发现端口,用户在通知栏直回配对码,码到即配。
     */
    fun startPairingService(context: Context) {
        setup(context)
        AdbPairingService.start(context)
    }

    /** 是否与设备配对成功过;true 时 UI 开关可直接连接,无需再走配对引导 */
    fun isPaired(): Boolean = keyStore?.getBoolean(PREF_PAIRED, false) ?: false

    private fun setPaired(value: Boolean) {
        keyStore?.edit()?.putBoolean(PREF_PAIRED, value)?.apply()
    }

    /** 配对成功回调(AdbPairingService 调用):开关落盘 + 启动重连 + 立即连接 */
    fun onPaired(context: Context) {
        setup(context)
        enabled = true
        setPaired(true)
        appContext!!.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PrefsKeys.ADB_PRIV_ENABLED, true).apply()
        if (key != null) {
            startReconnectLoop()
            scope.launch { connectOnceInternal() }
        }
    }

    /** 配对服务取用进程内缓存的 AdbKey(与连接使用同一密钥) */
    fun peekKey(): AdbKey? = key

    /**
     * 执行特权 shell 命令并收集输出
     * @return 输出文本;失败(未启用/未连接/超时/异常)返回 null,消费方自行降级
     */
    suspend fun exec(command: String): String? {
        if (!enabled || key == null) return null
        return try {
            withTimeout(EXEC_TIMEOUT_MS) {
                val c = ensureConnected() ?: return@withTimeout null
                val sb = StringBuilder()
                try {
                    c.shellCommand(command) { bytes -> sb.append(String(bytes)) }
                    lastSuccessAt = System.currentTimeMillis()
                    sb.toString()
                } catch (e: Throwable) {
                    Log.w(TAG, "exec 失败(${e.message}),标记断开")
                    onDisconnected()
                    null
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "exec 超时")
            onDisconnected()
            null
        } catch (e: Throwable) {
            Log.w(TAG, "exec 异常: ${e.message}")
            onDisconnected()
            null
        }
    }

    // ===== 内部实现 =====

    private suspend fun ensureConnected(): AdbClient? {
        connectMutex.withLock {
            if (_state.value == AdbState.CONNECTED) return client
            return connectOnceInternal()
        }
    }

    /** NSD 发现 + TLS 连接 + shell 自检(uid 必须 2000);调用方持有 connectMutex */
    private suspend fun connectOnceInternal(): AdbClient? {
        val ctx = appContext ?: return null
        val k = key ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (_state.value == AdbState.CONNECTED) return client

        // ① 环回直连优先(免发现、重启后仍可用;未受信时快速失败转 NSD,不弹窗)
        _state.value = AdbState.CONNECTING
        tryLoopback(ctx, allowUntrustedDialog = false)?.let { return it }
        // ② NSD 发现无线调试 TLS
        _state.value = AdbState.DISCOVERING
        val port = try {
            discoverPort(ctx, AdbMdns.TLS_CONNECT)
        } catch (t: Throwable) {
            Log.w(TAG, "NSD 发现异常: ${t.message}")
            null
        }
        if (port == null || port <= 0) {
            lastFailure = "NSD 未发现服务"
            _state.value = AdbState.DISCONNECTED
            logDiag(ctx, "DISCOVER_FAIL 12s 窗口未发现服务")
            return null
        }

        _state.value = AdbState.CONNECTING
        val attempt = try {
            AdbClient("127.0.0.1", port, k)
        } catch (e: Throwable) {
            lastFailure = describeFailure(e)
            logDiag(ctx, "CONNECT_FAIL $lastFailure (${e.javaClass.simpleName}: ${e.message})")
            _state.value = AdbState.DISCONNECTED
            return null
        }
        return try {
            attempt.connect()
            // 自检:必须是 shell(uid=2000)
            val id = StringBuilder()
            attempt.shellCommand("id") { bytes -> id.append(String(bytes)) }
            if (id.toString().contains("uid=2000")) {
                closeClientQuietly()
                client = attempt
                lastSuccessAt = System.currentTimeMillis()
                lastFailure = null
                _state.value = AdbState.CONNECTED
                Log.i(TAG, "特权通道已连接(端口 $port)")
                logDiag(ctx, "CONNECTED port=$port")
                // 幂等自动授权引导(独立协程:其内部 exec 会走 ensureConnected,
                // 若在持锁协程内调用会重入 connectMutex 死锁)
                AdbAutoGrant.onConnected(ctx)
                // 按载体模式拉起常驻服务(内置 libbfd / Shizuku),内置模式顺带推进自愈基座
                launchCarrier(ctx)
                attempt
            } else {
                Log.w(TAG, "连接自检失败: $id")
                lastFailure = "自检失败(id 非 shell 域)"
                attempt.close()
                _state.value = AdbState.DISCONNECTED
                logDiag(ctx, "SELF_CHECK_FAIL id=${id.take(60)}")
                null
            }
        } catch (e: Throwable) {
            lastFailure = describeFailure(e)
            runCatching { attempt.close() } // 失败路径必须关 socket,否则泄漏且干扰诊断
            if (isTlsAuthFailure(e)) {
                // 握手失败=设备不再信任本机公钥(如撤销授权/无线调试数据被重置)
                Log.w(TAG, "TLS 握手失败——设备不信任本机密钥,需重新配对: ${e.message}")
                setPaired(false)
                _state.value = AdbState.AUTH_FAILED
                logDiag(ctx, "AUTH_FAILED $lastFailure (${e.javaClass.simpleName})")
            } else {
                Log.w(TAG, "连接失败: ${e.message}")
                _state.value = AdbState.DISCONNECTED
                logDiag(ctx, "CONNECT_FAIL $lastFailure (${e.javaClass.simpleName}: ${e.message})")
            }
            null
        }
    }

    /**
     * 通道诊断日志:追加到应用外部 files 目录(vivo 屏蔽应用 logcat,
     * 主机 adb 可直接读取该文件做无感诊断)
     */
    internal fun logDiag(ctx: Context, line: String) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "adb_diag.log")
            if (f.length() > 64_000) f.writeText("")
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$ts $line\n")
        } catch (_: Throwable) {
        }
    }

    /** 无上下文重载(adb 包内部诊断用) */
    internal fun logDiag(line: String) {
        appContext?.let { logDiag(it, line) }
    }

    private const val LOOPBACK_PORT = 5555

    /**
     * 按载体模式拉起常驻服务;内置模式顺带推进自愈基座(均幂等)。
     * 内置 daemon 存活时绝不重复拉起——每次拉起都会换令牌+杀旧实例,
     * 与在途请求竞态会造成 Connection reset
     */
    private fun launchCarrier(ctx: Context) {
        when (PrivShell.carrierMode()) {
            PrivShell.CarrierMode.SHIZUKU -> ShizukuChannel.onConnected(ctx)
            PrivShell.CarrierMode.BUILTIN -> if (!BfdChannel.alive()) {
                scope.launch {
                    BfdChannel.startViaAdb(ctx)
                    PrivBaseline.onConnected(ctx)
                }
            }
        }
    }

    /**
     * 环回直连本机 adbd(127.0.0.1:5555,经典 A_AUTH 通道):
     * 免 NSD 发现、随 persist.adb.tcp.port 在重启后自动恢复,是自愈基座主路径。
     * @param allowUntrustedDialog true 时设备未受信会触发系统授权弹窗(仅基座验证;
     * 常规重连为 false——未受信快速失败转 NSD,避免反复打扰用户)
     * @return 连接成功且自检通过的客户端;失败返回 null(静默转 NSD,不算整体失败)
     */
    private suspend fun tryLoopback(ctx: Context, allowUntrustedDialog: Boolean): AdbClient? {
        val k = key ?: return null
        val attempt = AdbClient("127.0.0.1", LOOPBACK_PORT, k)
        return try {
            attempt.connect(allowUntrustedDialog)
            val id = StringBuilder()
            attempt.shellCommand("id") { bytes -> id.append(String(bytes)) }
            if (id.toString().contains("uid=2000")) {
                closeClientQuietly()
                client = attempt
                lastSuccessAt = System.currentTimeMillis()
                lastFailure = null
                _state.value = AdbState.CONNECTED
                Log.i(TAG, "特权通道已连接(环回 5555)")
                logDiag(ctx, "CONNECTED loopback5555")
                // 幂等自动授权引导(与 TLS 通道路径一致;独立协程:其内部 exec 会走
                // ensureConnected,若在持锁协程内调用会重入 connectMutex 死锁)
                AdbAutoGrant.onConnected(ctx)
                attempt
            } else {
                Log.w(TAG, "环回连接自检失败: $id")
                attempt.close()
                null
            }
        } catch (e: Throwable) {
            lastFailure = describeFailure(e)
            runCatching { attempt.close() }
            // 经典通道失败不参与 TLS 配对体系(不置 AUTH_FAILED),静默转 NSD
            Log.w(TAG, "环回连接失败: ${e.message}")
            if (allowUntrustedDialog) logDiag(ctx, "LOOPBACK_FAIL ${describeFailure(e)}")
            null
        }
    }

    /** 自愈基座用:验证环回直连是否已受信(允许触发一次性授权弹窗);探测后立即关闭 */
    suspend fun verifyLoopbackTrust(ctx: Context): Boolean {
        val k = key ?: return false
        val probe = AdbClient("127.0.0.1", LOOPBACK_PORT, k)
        return try {
            probe.connect(allowUntrustedDialog = true)
            val id = StringBuilder()
            probe.shellCommand("id") { bytes -> id.append(String(bytes)) }
            val ok = id.toString().contains("uid=2000")
            runCatching { probe.close() }
            ok
        } catch (e: Throwable) {
            runCatching { probe.close() }
            Log.w(TAG, "环回验证失败: ${e.message}")
            false
        }
    }

    /** 连接失败归类(上屏诊断用):区分对端关闭/读超时/端口拒绝/TLS 层失败 */
    private fun describeFailure(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is java.io.EOFException -> return "对端关闭连接"
                is java.net.SocketTimeoutException -> return "读超时"
                is java.net.ConnectException -> return "端口拒绝连接"
                is javax.net.ssl.SSLException -> return "TLS 握手失败"
            }
            t = t.cause
        }
        return e.javaClass.simpleName
    }

    /** 是否 TLS 认证层失败(区别于端口不可达:后者是 SocketException,继续退避重连即可) */
    private fun isTlsAuthFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SSLException) return true
            t = t.cause
        }
        return false
    }

    /** NSD 发现指定类型服务端口(AdbMdns 内部已做本机接口过滤与端口占用校验) */
    private suspend fun discoverPort(context: Context, serviceType: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(DISCOVER_TIMEOUT_MS) {
            val deferred = CompletableDeferred<Int>()
            val mdns = AdbMdns(context, serviceType) { port ->
                if (port > 0) deferred.complete(port)
            }
            mdns.start()
            try {
                deferred.await()
            } finally {
                mdns.stop()
            }
        }
    }

    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (isActive && enabled) {
                if (_state.value == AdbState.AUTH_FAILED) {
                    // 设备已不信任密钥,重连无意义且与厂商管控拉锯;暂停等待用户重新配对
                    Log.i(TAG, "设备不信任本机密钥,暂停自动重连(等待用户重新配对)")
                    break
                }
                if (_state.value != AdbState.CONNECTED) {
                    connectMutex.withLock {
                        // 退避读改写入锁,与亮屏重置(resetBackoffAndReconnect)互斥防交错
                        val ok = _state.value == AdbState.CONNECTED || connectOnceInternal() != null
                        backoffMs = if (ok) RECONNECT_MIN_MS else (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
                    }
                }
                delay(backoffMs)
            }
        }
    }

    /**
     * 亮屏/解锁触发的即时重连:重置退避并立即尝试一轮连接。
     * 只在断连且非 AUTH_FAILED 时动作;事件驱动,不引入任何轮询
     */
    private fun resetBackoffAndReconnect() {
        if (!enabled || key == null) return
        if (_state.value == AdbState.CONNECTED || _state.value == AdbState.AUTH_FAILED) return
        scope.launch {
            connectMutex.withLock {
                if (_state.value != AdbState.CONNECTED) {
                    backoffMs = RECONNECT_MIN_MS
                    connectOnceInternal()
                }
            }
        }
    }

    /** 注册亮屏解锁监听(仅一次;接收器自行判断开关与状态) */
    private fun registerUnlockTrigger(context: Context) {
        if (unlockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                resetBackoffAndReconnect()
            }
        }
        unlockReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun onDisconnected() {
        closeClientQuietly()
        if (_state.value != AdbState.NOT_PAIRED && _state.value != AdbState.AUTH_FAILED) {
            _state.value = AdbState.DISCONNECTED
        }
    }

    private fun closeClientQuietly() {
        try {
            client?.close()
        } catch (_: Throwable) {
        }
        client = null
    }
}
