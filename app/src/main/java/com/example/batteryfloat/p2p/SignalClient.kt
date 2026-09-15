package com.example.batteryfloat.p2p

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/**
 * WebSocket 信令客户端（对接自建 /opt/family-signal 服务）
 *
 * 特性：自动重连（指数退避 2s→30s）、心跳、注册/登出、
 * loc-req / loc-res 两种中继发送。所有回调在 [scope] 所在调度器投递。
 *
 * **主/备端点切换**（[backupUrl] 非空时生效）：
 * 信令服务是**有状态中继**——同一个家庭必须整体落在同一台服务器上，两台服务器
 * 同时对外服务会把家人拆到两边互相看不见（表现为"家人一直离线"且无任何报错）。
 * 因此这里只做"主优先、不可达才切备用"：
 * - 仅当**握手都没成功**（端点确实不可达）才计入失败，连续 [FAILS_BEFORE_SWITCH]
 *   次后切到备用；服务器可达但因限流等被拒绝不算端点故障，不会触发切换。
 * - 处于备用端点时按 [PRIMARY_PROBE_INTERVAL_MS] 主动回探主端点，探通即切回，
 *   把"部分家人在主、部分在备用"的分裂窗口压到最短。
 * - 生效端点进程内共享（[activeIndex]），保证服务与 UI 的临时查询落在同一台服务器。
 *
 * @param url 主信令地址（必填，非法/空由上层拦截）
 * @param backupUrl 备用信令地址（可选；空串即单端点，行为与未引入切换前一致）
 */
class SignalClient(
    private val url: String,
    private val backupUrl: String = "",
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

    /** 候选端点：主在前、备在后（去重；备用为空即单端点） */
    private val endpoints: List<String> = listOf(url, backupUrl)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** 主端点连续未握手成功次数（成功握手或切换后归零） */
    private var consecutiveFails = 0

    /** 已计过失败的连接实例（同一实例的 onClose 与 connect() 异常只计一次） */
    private var failureCountedFor: WebSocketClient? = null

    /** 备用端点期间的主动回探任务 */
    private var primaryProbeJob: Job? = null

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
        consecutiveFails = 0
        startPrimaryProbeLoop()
        openSocket()
    }

    /** 主动断开（停止重连） */
    fun disconnect() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        primaryProbeJob?.cancel()
        primaryProbeJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        // close() 对握手中的连接无效（握手照常完成后变僵尸），统一强断
        runCatching { client?.closeConnection(CloseFrame.ABNORMAL_CLOSE, "disconnected") }
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
        // 用当前生效端点：家庭码是否有主，只有"家庭实际所在的那台服务器"才答得准，
        // 问另一台可能得到相反答案（备用节点上没有这个家庭）。此处不参与切换，
        // 端点选择由家人服务的实例负责。
        val checker = object : WebSocketClient(URI.create(currentEndpoint())) {
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

        val endpointIndex = activeIndex.coerceIn(0, endpoints.size - 1)
        val target = endpoints[endpointIndex]
        Log.i(TAG, "connecting endpoint[" + (endpointIndex + 1) + "/" + endpoints.size + "]")
        _state.value = State.Connecting
        // 本次尝试是否完成握手：只有"握不上手"才判定该端点不可达；
        // 服务器可达但因限流/参数被拒（能握手）不算端点故障，不应触发切换
        var handshakeDone = false
        val ws = object : WebSocketClient(URI.create(target)) {

            override fun onOpen(handshakedata: ServerHandshake?) {
                // 僵尸连接防护：disconnect()/openSocket() 已替换 client 后，
                // 旧连接迟到的握手完成（握手中的 close() 无法中止）立即自断，
                // 防服务器侧同 uid 挂双连接 + 僵尸心跳
                if (stopped || client !== this) {
                    Log.i(TAG, "ws open after stopped/replaced, aborting")
                    runCatching { closeConnection(CloseFrame.ABNORMAL_CLOSE, "replaced") }
                    return
                }
                handshakeDone = true
                consecutiveFails = 0
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
                    SignalTypes.ERROR -> {
                        // 注册阶段的拒绝(rate_limited / bad_register / server_full)只回一条 error:
                        // 服务器既不回 registered 也不关连接,若只把错误转给 UI,客户端会永远停在
                        // "正在连接"(应用级心跳还替这条死连接续命),家人位置共享静默失效到重启应用。
                        // 故先上屏提示,再主动断开——onClose 会统一收尾并调度退避重连,
                        // 限流窗口(60 秒)过去后自动恢复。
                        scope.launch { onMessage?.invoke(msg) }
                        if (_state.value is State.Connecting) {
                            Log.w(TAG, "register rejected: " + (msg.code ?: msg.message))
                            runCatching {
                                closeConnection(CloseFrame.ABNORMAL_CLOSE, "register rejected")
                            }
                        }
                    }
                    else -> scope.launch { onMessage?.invoke(msg) }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                val detail = "code=" + code + " reason=" + reason + " remote=" + remote
                Log.i(TAG, "ws closed: " + detail)
                // 迟到的旧连接回调：不是当前 client 时不清理、不改状态、
                // 不触发重连（防误伤新连接的状态与心跳、防多余 openSocket）
                if (client !== this) return
                if (!handshakeDone) noteEndpointFailure(this)
                client = null
                heartbeatJob?.cancel()
                heartbeatJob = null
                if (_state.value is State.Connected) {
                    _state.value = State.Disconnected(detail)
                } else if (_state.value is State.Connecting) {
                    _state.value = State.Disconnected("connect failed")
                } else if (_state.value is State.PendingApproval) {
                    // 等待审核期间断线：状态必须回落，否则界面会一直停在"等待创建人审核"
                    _state.value = State.Disconnected(detail)
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
                noteEndpointFailure(ws)
                _state.value = State.Disconnected("connect failed: " + e.message)
                scheduleReconnect()
            }
    }

    /**
     * 记录一次"端点未握手成功"，累计到阈值后在主/备之间切换。
     *
     * 已处于备用端点时不再自动切回（避免两端点间来回抖动），切回只由回探负责。
     * 单端点（未配置备用）时本方法直接返回，行为与改造前一致。
     */
    private fun noteEndpointFailure(ws: WebSocketClient?) {
        if (ws != null && failureCountedFor === ws) return
        failureCountedFor = ws
        consecutiveFails++
        if (endpoints.size < 2 || activeIndex != 0) return
        if (consecutiveFails >= FAILS_BEFORE_SWITCH) {
            consecutiveFails = 0
            activeIndex = 1
            backoffMs = 2_000L
            Log.w(TAG, "主端点连续 " + FAILS_BEFORE_SWITCH + " 次未握手成功，切换到备用端点")
        }
    }

    /** 当前生效端点（下标越界时回落主端点） */
    private fun currentEndpoint(): String = endpoints[activeIndex.coerceIn(0, endpoints.size - 1)]

    /**
     * 备用端点期间的主动回探：定时用**临时连接**探测主端点是否恢复，探通即切回。
     *
     * 为什么要主动探：两端点并存时，若一部分家人的连接一直没断（留在主），
     * 另一部分已切到备用，家庭就被拆成两半且互相看不见——只有把已切换的客户端
     * 尽快拉回主端点，才能把这个分裂窗口压到最短。回探不注册、不改变任何状态。
     */
    private fun startPrimaryProbeLoop() {
        primaryProbeJob?.cancel()
        if (endpoints.size < 2) return
        primaryProbeJob = scope.launch {
            while (isActive) {
                delay(PRIMARY_PROBE_INTERVAL_MS)
                if (stopped || activeIndex == 0) continue
                if (probeEndpoint(endpoints[0])) {
                    Log.i(TAG, "主端点回探成功，切回主端点")
                    activeIndex = 0
                    backoffMs = 2_000L
                    // 断开当前（备用）连接，交由 onClose -> scheduleReconnect 重连到主端点
                    runCatching { client?.closeConnection(CloseFrame.NORMAL, "switch to primary") }
                } else {
                    Log.i(TAG, "主端点回探失败，继续使用备用端点")
                }
            }
        }
    }

    /**
     * 轻量连通性探测：临时建连、握手成功即判定可达并立即关闭（不注册、不发消息）。
     *
     * 用非阻塞的 connect() + 超时等待，避免阻塞 [scope] 所在的主线程。
     *
     * @return 是否握手成功
     */
    private suspend fun probeEndpoint(target: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        val probe = object : WebSocketClient(URI.create(target)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                result.complete(true)
                runCatching { close() }
            }

            override fun onMessage(message: String?) {}

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                result.complete(false)
            }

            override fun onError(ex: Exception?) {
                result.complete(false)
            }
        }
        runCatching { probe.connect() }.onFailure { result.complete(false) }
        val reachable = withTimeoutOrNull(PROBE_TIMEOUT_MS) { result.await() } ?: false
        runCatching { probe.closeConnection(CloseFrame.ABNORMAL_CLOSE, "probe finished") }
        return reachable
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

        /** 主端点连续多少次"未握手成功"后切到备用端点 */
        const val FAILS_BEFORE_SWITCH = 3

        /** 备用端点期间回探主端点的间隔（决定两端点并存的分裂窗口上限） */
        const val PRIMARY_PROBE_INTERVAL_MS = 120_000L

        /** 单次连通性探测的超时 */
        const val PROBE_TIMEOUT_MS = 8_000L

        /**
         * 当前生效端点下标（进程内共享）。
         * 家人服务与 UI 的临时查询必须落在同一台服务器上，否则家庭码查询会问到
         * 另一个节点、得到与家庭实际所在节点相反的答案。0=主，1=备用。
         */
        @Volatile
        var activeIndex = 0
    }
}