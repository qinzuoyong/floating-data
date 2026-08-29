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

        _state.value = AdbState.DISCOVERING
        val port = try {
            discoverPort(ctx, AdbMdns.TLS_CONNECT)
        } catch (t: Throwable) {
            Log.w(TAG, "NSD 发现异常: ${t.message}")
            null
        }
        if (port == null || port <= 0) {
            _state.value = AdbState.DISCONNECTED
            return null
        }

        _state.value = AdbState.CONNECTING
        return try {
            val c = AdbClient("127.0.0.1", port, k)
            c.connect()
            // 自检:必须是 shell(uid=2000)
            val id = StringBuilder()
            c.shellCommand("id") { bytes -> id.append(String(bytes)) }
            if (id.toString().contains("uid=2000")) {
                closeClientQuietly()
                client = c
                lastSuccessAt = System.currentTimeMillis()
                _state.value = AdbState.CONNECTED
                Log.i(TAG, "特权通道已连接(端口 $port)")
                // 幂等自动授权引导(独立协程:其内部 exec 会走 ensureConnected,
                // 若在持锁协程内调用会重入 connectMutex 死锁)
                AdbAutoGrant.onConnected(ctx)
                // 借本通道拉起 Shizuku 常驻服务(无线调试关闭后特权能力的载体)
                ShizukuChannel.onConnected(ctx)
                c
            } else {
                Log.w(TAG, "连接自检失败: $id")
                c.close()
                _state.value = AdbState.DISCONNECTED
                null
            }
        } catch (e: Throwable) {
            if (isTlsAuthFailure(e)) {
                // 握手失败=设备不再信任本机公钥(如撤销授权/无线调试数据被重置)
                Log.w(TAG, "TLS 握手失败——设备不信任本机密钥,需重新配对: ${e.message}")
                setPaired(false)
                _state.value = AdbState.AUTH_FAILED
            } else {
                Log.w(TAG, "连接失败: ${e.message}")
                _state.value = AdbState.DISCONNECTED
            }
            null
        }
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
                    val ok = connectMutex.withLock {
                        if (_state.value == AdbState.CONNECTED) true else connectOnceInternal() != null
                    }
                    backoffMs = if (ok) RECONNECT_MIN_MS else (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
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
        backoffMs = RECONNECT_MIN_MS
        scope.launch {
            connectMutex.withLock {
                if (_state.value != AdbState.CONNECTED) connectOnceInternal()
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
