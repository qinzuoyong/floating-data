package com.example.batteryfloat.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.batteryfloat.BuildConfig
import com.example.batteryfloat.R
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.location.OnDemandLocationProvider
import com.example.batteryfloat.notif.Notifs
import com.example.batteryfloat.p2p.SignalClient
import com.example.batteryfloat.p2p.SignalMessage
import com.example.batteryfloat.p2p.SignalTypes
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 家人位置共享后台服务（纯信令中继，无 WebRTC；2026-09 起不再前台运行）
 *
 * 职责：常驻后台保持信令连接（WebSocket）→ 按需响应家人位置请求
 * （一次性定位 → 信令回传）；收到家人位置时写入 [FamilyStore]，Compose UI 实时观察。
 *
 * 保活依赖：本服务为普通后台服务（无专属前台通知），进程后台常驻依赖同进程的
 * 悬浮窗前台服务（[FloatingWindowService]）；悬浮窗未运行时本服务可被系统回收。
 *
 * 权限前置：需已授予定位权限（FINE/COARSE），否则降级提示。
 */
class FamilyLocationService : Service() {

    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()

    private var store: FamilyStore? = null
    private var provider: OnDemandLocationProvider? = null
    private var signal: SignalClient? = null

    /** 信令状态收集任务:重建通道前取消,避免旧实例的 collector 在服务存活期内累积泄漏 */
    private var stateCollectJob: Job? = null

    /**
     * 本机已发出、尚待应答的位置请求(uid → 受理时间戳)。
     * 仅接受"曾请求过位置"的成员在有效期内回传的 loc-res,防止房间内任意成员
     * 伪造位置或注入幽灵成员;同时用于同一成员重复请求的合并(耗电保护)。
     */
    private val requestedLocations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 前置检查(定位权限);本服务不再前台通知,后台常驻依赖悬浮窗前台服务保活进程
        if (!ensureCanRun()) {
            Log.w(TAG, "缺少定位权限，家人位置共享无法启动")
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REQUEST_LOCATION -> {
                val uid = intent.getStringExtra(EXTRA_UID) ?: ""
                if (uid.isNotBlank()) requestMemberLocation(uid)
                return START_STICKY
            }
            ACTION_APPROVE_JOIN -> {
                val uid = intent.getStringExtra(EXTRA_JOIN_UID) ?: ""
                if (uid.isNotBlank()) signal?.sendJoinApprove(uid)
                return START_STICKY
            }
            ACTION_REJECT_JOIN -> {
                val uid = intent.getStringExtra(EXTRA_JOIN_UID) ?: ""
                if (uid.isNotBlank()) signal?.sendJoinReject(uid)
                return START_STICKY
            }
            else -> {
                // ACTION_START（含重复点击）= 全量重建连接（家庭码可能已变更）
                setup()
            }
        }
        return START_STICKY
    }

    /** 请求指定成员的位置（UI 调用入口）；未连接时上屏提示，不再静默丢弃 */
    fun requestMemberLocation(uid: String) {
        val sent = signal?.sendLocReq(uid) == true
        if (!sent) {
            postNotice(getString(R.string.family_error_not_connected))
            return
        }
        // 记录"已请求"：后续只接受该成员在有效期内的应答（见 handleSignal → LOC_RES）
        requestedLocations[uid] = System.currentTimeMillis()
        // 顺带回收过期记录,避免长时间运行后无界增长
        val expireBefore = System.currentTimeMillis() - LOC_REQ_TTL_MS
        requestedLocations.entries.removeAll { it.value < expireBefore }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        stateCollectJob?.cancel()
        stateCollectJob = null
        requestedLocations.clear()
        signal?.disconnect()
        provider?.close()
        workScope.cancel()
        super.onDestroy()
    }

    // ===== 内部 =====

    /**
     * 启动前置检查:定位权限是否就绪
     *
     * 本服务自 2026-09 起不再作为前台服务(去除独立常驻通知),后台常驻由同进程的
     * 悬浮窗前台服务保活;缺定位权限时发一条短时停用提示并返回 false。
     */
    private fun ensureCanRun(): Boolean {
        val hasLoc = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasLoc) {
            Log.w(TAG, "location permission not granted")
            runCatching {
                // 本服务不再 startForeground,onCreate 不建渠道;缺权限停用提示需先确保渠道存在
                Notifs.ensureChannels(this)
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.notify(
                    Notifs.ID_FAMILY_NOTICE,
                    Notifs.familyStoppedNotice(this, "缺少定位权限，请允许后重试")
                )
            }
            return false
        }
        return true
    }

    private fun setup() {
        if (!ensureCanRun()) {
            stopSelf()
            return
        }
        val s = FamilyStore.get(this)
        store = s
        provider?.close() // 服务重复 start 重建通道前,先释放旧实例的定位线程池
        provider = OnDemandLocationProvider(this)

        val code = s.familyCode()
        if (code.isBlank()) {
            Log.i(TAG, "未加入家庭，仅驻留前台")
            return
        }

        // 信令地址必须显式配置（local.properties → BuildConfig，代码不再内置兜底地址）。
        // 缺失/非法时不建立连接并给出明确提示，避免连到未知服务器或运行时抛异常。
        val signalUrl = BuildConfig.SIGNAL_URL
        if (!signalUrl.startsWith("ws://") && !signalUrl.startsWith("wss://")) {
            Log.e(TAG, "SIGNAL_URL 未配置或非法，家人位置共享不可用")
            postNotice(getString(R.string.family_error_no_signal_config))
            return
        }

        // 重建通道（幂等：先清理旧连接）
        signal?.disconnect()

        val sig = SignalClient(signalUrl).also {
            it.onMessage = ::handleSignal
        }
        signal = sig

        // 重建通道前先取消上一条状态收集任务,否则每次 setup 都会留下一个旧实例的 collector
        stateCollectJob?.cancel()
        stateCollectJob = workScope.launch {
            sig.state.collect { _connection.value = it }
        }
        sig.connect(code, s.myUid(), s.myName())
        Log.i(TAG, "signal connecting room=" + code)
    }

    /**
     * 信令消息入口：任何远端数据异常都在此收敛，绝不冒泡到线程级。
     * 远端报文（含 payload）完全不可信，反序列化/字段异常必须降级为"丢弃本条 + 留痕"，
     * 否则会沿 Dispatchers.Main 的协程抛出并令进程崩溃（可被房间内任意成员远程触发）。
     */
    private fun handleSignal(msg: SignalMessage) {
        try {
            dispatchSignal(msg)
        } catch (e: Exception) {
            Log.w(TAG, "信令处理失败,已丢弃该条 type=" + msg.type, e)
        }
    }

    /** 信令消息路由（Main 线程回调） */
    private fun dispatchSignal(msg: SignalMessage) {
        val s = store ?: return
        when (msg.type) {
            SignalTypes.REGISTERED -> {
                // 注册成功（创建人或被批准）：清除等待审核状态
                s.setJoinState(FamilyStore.JoinState.NONE)
                val roster = msg.roster
                if (roster != null) {
                    // 新协议：以服务器名册全量重建本地成员列表（家庭实际成员，含离线）
                    Log.i(TAG, "registered, roster=" + roster.size)
                    s.syncRoster(roster)
                } else {
                    // 兼容旧服务器：仅在线成员逐个 upsert
                    val peers = msg.peers ?: emptyList()
                    Log.i(TAG, "registered, peers=" + peers.size)
                    for (peer in peers) {
                        s.upsertMember(peer.uid, peer.name, peer.online)
                    }
                }
            }

            SignalTypes.JOIN_PENDING -> {
                Log.i(TAG, "加入申请已提交，等待创建人审核")
                s.setJoinState(FamilyStore.JoinState.PENDING)
            }

            SignalTypes.JOIN_REJECTED -> {
                Log.i(TAG, "加入申请被拒绝")
                s.setJoinState(FamilyStore.JoinState.REJECTED)
            }

            SignalTypes.JOIN_REQUEST -> {
                val uid = msg.uid ?: return
                Log.i(TAG, "收到加入申请: " + (msg.name ?: uid))
                s.addPendingJoin(uid, msg.name ?: "")
            }

            SignalTypes.PRESENCE -> {
                val uid = msg.uid ?: return
                val online = msg.online == true
                if (online) {
                    s.upsertMember(uid, msg.name ?: "", true)
                } else {
                    s.markOffline(uid)
                }
            }

            SignalTypes.LOC_REQ -> {
                val from = msg.from ?: return
                if (!s.allowLocReq()) {
                    Log.i(TAG, "ignore loc-req from " + from + " (privacy off)")
                    return
                }
                val p = provider
                if (p == null) {
                    Log.w(TAG, "provider missing, cannot answer " + from)
                    return
                }
                // 同一成员在合并窗口内的重复请求直接忽略：防止家人连点把 GNSS 拉成持续采集（耗电）
                val now = System.currentTimeMillis()
                val last = requestedLocations[from]
                if (last != null && now - last < LOC_REQ_COOLDOWN_MS) {
                    Log.i(TAG, "ignore duplicated loc-req from " + from)
                    return
                }
                requestedLocations[from] = now
                workScope.launch {
                    // 先粗后精多次回传：NETWORK 粗定位先到先发（对方几秒内出图），
                    // GPS 更优结果到达后再次回传自动覆盖（服务器中继与存储均幂等）
                    var sent = 0
                    withContext(Dispatchers.Default) {
                        p.currentLocationFlow().collect { loc ->
                            sent++
                            signal?.sendLocRes(from, loc)
                        }
                    }
                    if (sent == 0) Log.w(TAG, "location unavailable, cannot answer " + from)
                }
            }

            SignalTypes.LOC_RES -> {
                val from = msg.from ?: return
                // 只接受本机曾请求过、且在有效期内的成员应答：
                // 服务端中继不校验 from 是否属于名册，房间内任意成员都可主动投递 loc-res，
                // 若不校验即可伪造位置并向本地注入"幽灵成员"。
                val requestedAt = requestedLocations[from]
                if (requestedAt == null) {
                    Log.w(TAG, "drop unsolicited loc-res from " + from)
                    return
                }
                if (System.currentTimeMillis() - requestedAt > LOC_RES_TTL_MS) {
                    Log.w(TAG, "drop expired loc-res from " + from)
                    return
                }
                val loc = msg.payload?.let {
                    runCatching {
                        gson.fromJson(it, com.example.batteryfloat.p2p.LocationPayload::class.java)
                    }.getOrNull()
                } ?: return
                if (!isPlausibleLocation(loc)) {
                    Log.w(TAG, "drop invalid loc-res payload from " + from)
                    return
                }
                s.updateLocation(from, loc)
            }

            SignalTypes.ERROR -> {
                Log.w(TAG, "signal error: " + (msg.message ?: msg.code))
                // 服务器回执上屏：目标离线等错误此前只打日志，按钮像"没反应"
                postNotice(
                    when (msg.code) {
                        "offline" -> getString(R.string.family_error_offline)
                        else -> getString(R.string.family_error_generic, msg.message ?: msg.code ?: "")
                    }
                )
            }
        }
    }

    /**
     * 远端位置合理性校验：越界、零值（0,0）、非法精度、明显未来的时间戳一律拒绝。
     * 这些值只会来自不可信的远端报文或解析退化（Gson 对缺失数值字段会填 0.0）。
     */
    private fun isPlausibleLocation(loc: com.example.batteryfloat.p2p.LocationPayload): Boolean {
        if (!loc.lat.isFinite() || !loc.lng.isFinite()) return false
        if (loc.lat < -90.0 || loc.lat > 90.0) return false
        if (loc.lng < -180.0 || loc.lng > 180.0) return false
        if (loc.lat == 0.0 && loc.lng == 0.0) return false
        if (loc.accuracy.isNaN() || loc.accuracy < 0f || loc.accuracy > 1_000_000f) return false
        // 允许 5 分钟时钟偏差；更远的"未来时间"视为伪造
        return loc.ts <= System.currentTimeMillis() + 5 * 60_000L
    }

    companion object {
        private const val TAG = "FamilyLocationService"

        /** 同一成员重复位置请求的合并窗口（忽略窗口内的重复请求，保护 GNSS 采集功耗） */
        private const val LOC_REQ_COOLDOWN_MS = 20_000L

        /** 已记录请求的保留时长（超过即回收，防长时间运行时 map 无界增长） */
        private const val LOC_REQ_TTL_MS = 30 * 60_000L

        /** 位置应答有效期：超过该时长才到达的应答视为过期并丢弃 */
        private const val LOC_RES_TTL_MS = 5 * 60_000L

        /** 服务是否在运行（UI 查询用；替代已废弃的 ActivityManager.getRunningServices） */
        @Volatile
        var isRunning = false
            private set

        const val ACTION_START = "com.yongge.batteryfloat.action.FAMILY_START"
        const val ACTION_STOP = "com.yongge.batteryfloat.action.FAMILY_STOP"
        const val ACTION_REQUEST_LOCATION = "com.yongge.batteryfloat.action.FAMILY_REQ_LOC"
        const val ACTION_APPROVE_JOIN = "com.yongge.batteryfloat.action.FAMILY_APPROVE_JOIN"
        const val ACTION_REJECT_JOIN = "com.yongge.batteryfloat.action.FAMILY_REJECT_JOIN"
        const val EXTRA_UID = "uid"
        const val EXTRA_JOIN_UID = "join_uid"

        private val _connection = MutableStateFlow<SignalClient.State>(SignalClient.State.Idle)
        /** 信令连接状态（服务内收集，UI 观察） */
        val connection: StateFlow<SignalClient.State> = _connection.asStateFlow()

        private val _notice = MutableStateFlow<String?>(null)
        /** 临时提示（对方离线/未连接等，UI 上屏后自动清除） */
        val notice: StateFlow<String?> = _notice.asStateFlow()

        /** 清除当前提示（UI 展示数秒后调用） */
        fun clearNotice() {
            _notice.value = null
        }

        /** 上屏一条临时提示（重复发送以最新为准） */
        private fun postNotice(text: String) {
            _notice.value = text
        }

        /**
         * 启动/重建家人共享后台服务
         *
         * 本服务为非前台服务（不再 startForeground），故改用普通 startService：
         * 若仍沿用 startForegroundService，系统会因服务未在 5 秒内转前台而抛
         * ForegroundServiceDidNotStartInTimeException 崩溃。
         * 权限前置校验：缺少定位权限时不启动服务，由 UI 层引导授权。
         */
        fun start(context: Context) {
            val hasLoc = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            if (!hasLoc) {
                Log.w(TAG, "start skipped: 缺少定位权限")
                return
            }
            context.startService(
                Intent(context, FamilyLocationService::class.java).setAction(ACTION_START)
            )
        }

        /** 停止服务 */
        fun stop(context: Context) {
            context.startService(
                Intent(context, FamilyLocationService::class.java).setAction(ACTION_STOP)
            )
        }

        /** 请求指定成员的位置（家人页「获取位置」按钮） */
        fun requestLocation(context: Context, uid: String) {
            context.startService(
                Intent(context, FamilyLocationService::class.java)
                    .setAction(ACTION_REQUEST_LOCATION)
                    .putExtra(EXTRA_UID, uid)
            )
        }

        /** 创建人批准加入申请 */
        fun approveJoin(context: Context, uid: String) {
            context.startService(
                Intent(context, FamilyLocationService::class.java)
                    .setAction(ACTION_APPROVE_JOIN)
                    .putExtra(EXTRA_JOIN_UID, uid)
            )
        }

        /** 创建人拒绝加入申请 */
        fun rejectJoin(context: Context, uid: String) {
            context.startService(
                Intent(context, FamilyLocationService::class.java)
                    .setAction(ACTION_REJECT_JOIN)
                    .putExtra(EXTRA_JOIN_UID, uid)
            )
        }
    }
}