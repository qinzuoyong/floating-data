package com.example.batteryfloat.p2p

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/**
 * WebSocket 信令客户端（对接自建 /opt/family-signal 服务）
 *
 * 特性：自动重连（指数退避 2s→30s）、心跳、注册/登出、
 * loc-req / loc-res 两种中继发送。所有回调在 [scope] 所在调度器投递。
 */
class SignalClient(
    private val url: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {

    sealed interface State {
        /** 未启动/已停止 */
        data object Idle : State
        /** 正在连接 */
        data object Connecting : State
        /** 已连接并注册成功（创建人或已批准成员） */
        data class Connected(val room: String, val uid: String) : State
        /** 已注册但等待创建人审核（加入者） */
        data class PendingApproval(val room: String) : State
        /** 连接断开（reason 用于日志） */
        data class Disconnected(val reason: String) : State
    }

    private val gson = Gson()
    private val _state = MutableStateFlow<State>(State.Idle)
    /** 连接状态，UI 可观察（家人页连接指示） */
    val state: StateFlow<State> = _state.asStateFlow()

    /** 消息回调（在 Main 线程） */
    var onMessage: ((SignalMessage) -> Unit)? = null

    /** 连接断开回调（含主动断开；供上层感知掉线） */
    var onDisconnected: ((String) -> Unit)? = null

    private var client: WebSocketClient? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var backoffMs = 2_000L
    private var stopped = false

    /** 注册身份（重连时复用） */
    private var room: String? = null
    private var uid: String? = null
    private var name: String? = null

    /**
     * 连接并注册
     *
     * @param room 6 位家庭码
     * @param uid 我的设备唯一标识
     * @param name 我的备注名（展示给家人）
     */
    fun connect(room: String, uid: String, name: String) {
        this.room = room
        this.uid = uid
        this.name = name
        stopped = false
        backoffMs = 2_000L
        openSocket()
    }

    /** 主动断开（停止重连） */
    fun disconnect() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { client?.close() }
        client = null
        _state.value = State.Idle
    }

    // ===== 发送 =====

    /**
     * 请求指定成员的位置
     *
     * @return 是否成功发出（false=未连接/发送失败，调用方可据此提示用户）
     */
    fun sendLocReq(to: String): Boolean =
        sendRaw(
            JsonObject().apply {
                addProperty("type", SignalTypes.LOC_REQ)
                addProperty("to", to)
            }
        )

    /** 回复位置给请求方（允许对同一请求多次发送：先粗后精） */
    fun sendLocRes(to: String, loc: LocationPayload): Boolean =
        sendRaw(
            JsonObject().apply {
                addProperty("type", SignalTypes.LOC_RES)
                addProperty("to", to)
                add("payload", gson.toJsonTree(loc))
            }
        )

    /** 创建人批准加入申请 */
    fun sendJoinApprove(targetUid: String) {
        sendRaw(
            JsonObject().apply {
                addProperty("type", SignalTypes.JOIN_APPROVE)
                addProperty("uid", targetUid)
            }
        )
    }

    /** 创建人拒绝加入申请 */
    fun sendJoinReject(targetUid: String) {
        sendRaw(
            JsonObject().apply {
                addProperty("type", SignalTypes.JOIN_REJECT)
                addProperty("uid", targetUid)
            }
        )
    }

    /**
     * 查询家庭码是否被占用（独立临时连接，不注册；用于创建/加入家庭前提示）
     *
     * 带 15s 超时兜底：服务器无响应时关闭临时连接并按"已占用"回调，
     * 避免线程与 socket 悬置、调用方永久无结果。
     *
     * @param room 6 位家庭码
     * @param onResult 结果回调（Main 线程）：exists=是否已有家庭，ownerName=创建人备注名
     */
    fun checkRoom(room: String, onResult: (exists: Boolean, ownerName: String?) -> Unit) {
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val checker = object : WebSocketClient(URI.create(url)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                send(
                    gson.toJson(
                        JsonObject().apply {
                            addProperty("type", SignalTypes.ROOM_CHECK)
                            addProperty("room", room)
                        }
                    )
                )
            }

            override fun onMessage(message: String?) {
                val raw = message ?: return
                val msg = try {
                    gson.fromJson(raw, SignalMessage::class.java)
                } catch (e: Exception) {
                    return
                }
                if (msg.type == SignalTypes.ROOM_CHECK_RES) {
                    if (!answered.compareAndSet(false, true)) return
                    val exists = msg.exists == true
                    scope.launch {
                        onResult(exists, msg.ownerName)
                        runCatching { close() }
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                // 未等到 room-check-res 即被关闭(服务器拒绝/网络断)：按已占用兜底回调
                if (answered.compareAndSet(false, true)) {
                    scope.launch { onResult(true, null) }
                }
            }

            override fun onError(ex: Exception?) {}
        }
        // 超时兜底：到期仍未有结果则关连接并回调，防临时连接悬置
        scope.launch {
            delay(ROOM_CHECK_TIMEOUT_MS)
            if (answered.compareAndSet(false, true)) {
                runCatching { checker.close() }
                onResult(true, null)
            }
        }
        runCatching { checker.connect() }
            .onFailure {
                if (answered.compareAndSet(false, true)) {
                    scope.launch { onResult(true, null) } // 查询失败按"已占用"处理（走加入流程兜底）
                }
            }
    }

    // ===== 内部 =====

    private fun sendRaw(obj: JsonObject): Boolean {
        val c = client
        if (c == null || c.readyState != org.java_websocket.enums.ReadyState.OPEN) {
            Log.w(TAG, "send skipped: not connected")
            return false
        }
        return try {
            c.send(gson.toJson(obj))
            true
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            false
        }
    }

    private fun openSocket() {
        val room = this.room ?: return
        val uid = this.uid ?: return
        val name = this.name ?: ""
        if (stopped) return

        _state.value = State.Connecting
        val ws = object : WebSocketClient(URI.create(url)) {

            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "ws open, registering room=" + room + " uid=" + uid)
                backoffMs = 2_000L
                startHeartbeat()
                val reg = JsonObject().apply {
                    addProperty("type", SignalTypes.REGISTER)
                    addProperty("room", room)
                    addProperty("uid", uid)
                    addProperty("name", name)
                }
                send(gson.toJson(reg))
            }

            override fun onMessage(message: String?) {
                val raw = message ?: return
                val msg = try {
                    gson.fromJson(raw, SignalMessage::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "bad message: " + raw)
                    return
                }
                when (msg.type) {
                    SignalTypes.REGISTERED -> {
                        _state.value = State.Connected(room, uid)
                        scope.launch { onMessage?.invoke(msg) }
                    }
                    SignalTypes.JOIN_PENDING -> {
                        _state.value = State.PendingApproval(room)
                        scope.launch { onMessage?.invoke(msg) }
                    }
                    else -> scope.launch { onMessage?.invoke(msg) }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                val detail = "code=" + code + " reason=" + reason + " remote=" + remote
                Log.i(TAG, "ws closed: " + detail)
                heartbeatJob?.cancel()
                heartbeatJob = null
                // 仅当当前引用仍是本连接时才清空,防旧连接迟到的 onClose 误清新连接
                if (client === this) client = null
                if (_state.value is State.Connected) {
                    _state.value = State.Disconnected(detail)
                } else if (_state.value is State.Connecting) {
                    _state.value = State.Disconnected("connect failed")
                }
                onDisconnected?.invoke(detail)
                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.w(TAG, "ws error: " + ex?.message)
                // 由 onClose 统一收尾（java-WebSocket 出错后必回调 onClose）
            }
        }
        client = ws
        runCatching { ws.connect() }
            .onFailure { e ->
                Log.w(TAG, "connect() failed", e)
                client = null
                _state.value = State.Disconnected("connect failed: " + e.message)
                scheduleReconnect()
            }
    }

    /**
     * 应用级心跳：每 25 秒发一次 ping（服务器回 pong，客户端忽略 pong 消息）。
     * 补足协议层心跳：缩短半开连接感知时间，防止 doze/NAT 超时后请求黑洞。
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendRaw(JsonObject().apply { addProperty("type", SignalTypes.PING) })
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            openSocket()
        }
    }

    private companion object {
        const val TAG = "SignalClient"

        /** 应用级心跳间隔（服务器心跳 30s，客户端 25s 错开且小于常见 NAT 空闲超时） */
        const val HEARTBEAT_INTERVAL_MS = 25_000L

        /** 家庭码占用查询超时：到期无响应按"已占用"兜底回调并关闭临时连接 */
        const val ROOM_CHECK_TIMEOUT_MS = 15_000L
    }
}