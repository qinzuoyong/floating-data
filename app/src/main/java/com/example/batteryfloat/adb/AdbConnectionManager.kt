package com.example.batteryfloat.adb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.batteryfloat.PrefsKeys
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    PAIRING
}

/**
 * ADB 无线调试特权通道管理器(单例,移植协议栈的编排层)
 *
 * - 持有 AdbKey(本机加密存储)与 AdbClient 常驻连接
 * - NSD 发现端口:开机/重启后端口漫游,免配对自动重连
 * - 对外提供 [exec]:特权 shell 命令,失败返回 null 由消费方降级(批次 3 接电池采样)
 * - 断线指数退避重连(3s 起,上限 15 分钟);[exec] 调用会立即触发一次连接尝试
 */
object AdbConnectionManager {

    private const val TAG = "AdbConnManager"
    private const val ADB_PREFS_NAME = "adb_prefs"
    private const val KEY_NAME = "batteryfloat@local"

    private const val DISCOVER_TIMEOUT_MS = 12_000L
    private const val EXEC_TIMEOUT_MS = 4_000L
    private const val RECONNECT_MIN_MS = 3_000L
    private const val RECONNECT_MAX_MS = 15 * 60_000L

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

    private val _state = MutableStateFlow(AdbState.NOT_PAIRED)
    val state: StateFlow<AdbState> = _state

    val isReady: Boolean get() = _state.value == AdbState.CONNECTED

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
     * 配对(由配对对话框驱动):NSD 发现 pairing 服务 → SPAKE2 配对 → 成功后自动连接
     * @return true=配对成功(连接已在后台进行)
     */
    suspend fun pair(pairCode: String): Boolean {
        val ctx = appContext ?: return false
        val k = key ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        _state.value = AdbState.PAIRING
        return try {
            val port = discoverPort(ctx, AdbMdns.TLS_PAIRING)
            if (port == null) {
                Log.w(TAG, "未发现配对服务(无线调试未开启?)")
                _state.value = AdbState.NOT_PAIRED
                return false
            }
            Log.i(TAG, "配对服务端口: $port")
            val ok = AdbPairingClient("127.0.0.1", port, pairCode, k).use { it.start() }
            Log.i(TAG, "配对结果: $ok")
            _state.value = if (ok) AdbState.DISCONNECTED else AdbState.NOT_PAIRED
            if (ok) {
                startReconnectLoop()
                scope.launch { connectOnceInternal() }
            }
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "配对异常: ${e.message}", e)
            _state.value = AdbState.NOT_PAIRED
            false
        }
    }

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
                _state.value = AdbState.CONNECTED
                Log.i(TAG, "特权通道已连接(端口 $port)")
                c
            } else {
                Log.w(TAG, "连接自检失败: $id")
                c.close()
                _state.value = AdbState.DISCONNECTED
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "连接失败: ${e.message}")
            _state.value = AdbState.DISCONNECTED
            null
        }
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
            var backoff = RECONNECT_MIN_MS
            while (isActive && enabled) {
                if (_state.value != AdbState.CONNECTED) {
                    val ok = connectMutex.withLock {
                        if (_state.value == AdbState.CONNECTED) true else connectOnceInternal() != null
                    }
                    backoff = if (ok) RECONNECT_MIN_MS else (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
                }
                delay(backoff)
            }
        }
    }

    private fun onDisconnected() {
        closeClientQuietly()
        if (_state.value != AdbState.NOT_PAIRED) {
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
