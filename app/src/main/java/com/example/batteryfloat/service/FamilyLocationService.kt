package com.example.batteryfloat.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 家人位置共享前台服务（纯信令中继，无 WebRTC）
 *
 * 职责：常驻后台保持信令连接（WebSocket）→ 按需响应家人位置请求
 * （一次性定位 → 信令回传）；收到家人位置时写入 [FamilyStore]，Compose UI 实时观察。
 *
 * 权限前置：需已授予定位权限（FINE/COARSE）与通知权限，否则降级提示。
 */
class FamilyLocationService : Service() {

    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()

    private var store: FamilyStore? = null
    private var provider: OnDemandLocationProvider? = null
    private var signal: SignalClient? = null

    override fun onCreate() {
        super.onCreate()
        // 通知渠道必须先于 startForeground 创建，否则 "invalid channel" 崩溃
        Notifs.ensureChannels(this)
        if (!ensureForeground()) {
            Log.w(TAG, "缺少定位/通知权限，家人位置共享无法常驻")
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
        if (!sent) postNotice(getString(R.string.family_error_not_connected))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        provider?.stopContinuous()
        signal?.disconnect()
        workScope.cancel()
        super.onDestroy()
    }

    // ===== 内部 =====

    /** 前台通知 + 权限检查；失败返回 false */
    private fun ensureForeground(): Boolean {
        val hasLoc = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasLoc) {
            Log.w(TAG, "location permission not granted")
            runCatching {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.notify(
                    Notifs.ID_FAMILY_NOTICE,
                    Notifs.familyStoppedNotice(this, "缺少定位权限，请允许后重试")
                )
            }
            return false
        }
        return try {
            val notification: Notification = Notifs.familyForeground(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifs.ID_FAMILY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(Notifs.ID_FAMILY, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun setup() {
        if (!ensureForeground()) {
            stopSelf()
            return
        }
        val s = FamilyStore.get(this)
        store = s
        provider?.stopContinuous() // 重建通道前先摘除旧订阅，防监听器泄漏
        val p = OnDemandLocationProvider(this)
        provider = p

        val code = s.familyCode()
        if (code.isBlank()) {
            Log.i(TAG, "未加入家庭，仅驻留前台")
            return
        }

        // 常驻定位缓存（GPS 60s + NETWORK 30s 周期刷新）：loc-req 到达时直接秒回
        // 精确缓存，不再请求时冷启动定位（参照 Traccar/OwnTracks 常驻订阅模式）
        p.startContinuous()

        // 重建通道（幂等：先清理旧连接）
        signal?.disconnect()

        val sig = SignalClient(BuildConfig.SIGNAL_URL).also {
            it.onMessage = ::handleSignal
        }
        signal = sig

        workScope.launch {
            sig.state.collect { _connection.value = it }
        }
        sig.connect(code, s.myUid(), s.myName())
        Log.i(TAG, "signal connecting room=" + code)
    }

    /** 信令消息路由（Main 线程回调） */
    private fun handleSignal(msg: SignalMessage) {
        val s = store ?: return
        when (msg.type) {
            SignalTypes.REGISTERED -> {
                val peers = msg.peers ?: emptyList()
                Log.i(TAG, "registered, peers=" + peers.size)
                // 注册成功（创建人或被批准）：清除等待审核状态
                s.setJoinState(FamilyStore.JoinState.NONE)
                for (peer in peers) {
                    s.upsertMember(peer.uid, peer.name, peer.online)
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
                // 常驻缓存优先：GPS 级缓存秒回，不冷启动定位
                val cached = p.freshCached()
                if (cached != null) {
                    signal?.sendLocRes(from, cached)
                }
                // 缓存缺失或精度差（>50m）才走并发定位流，且只补发比缓存更优的结果
                if (cached == null || cached.accuracy > GOOD_ACCURACY_M) {
                    workScope.launch {
                        var sent = 0
                        val threshold = cached?.accuracy
                        withContext(Dispatchers.Default) {
                            p.currentLocationFlow().collect { loc ->
                                if (threshold == null || loc.accuracy < threshold) {
                                    sent++
                                    signal?.sendLocRes(from, loc)
                                }
                            }
                        }
                        if (sent == 0 && cached == null) {
                            Log.w(TAG, "location unavailable, cannot answer " + from)
                        }
                    }
                }
            }

            SignalTypes.LOC_RES -> {
                val from = msg.from ?: return
                val loc = msg.payload?.let { gson.fromJson(it, com.example.batteryfloat.p2p.LocationPayload::class.java) }
                if (loc != null) {
                    s.updateLocation(from, loc)
                }
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

    companion object {
        private const val TAG = "FamilyLocationService"

        /** 缓存精度阈值：优于该值（米）直接秒回，不再启动并发定位流 */
        private const val GOOD_ACCURACY_M = 50f

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
         * 启动/重建家人共享前台服务
         *
         * 权限前置校验：缺少定位权限时不启动服务（避免 Android 14
         * startForegroundService 后 5 秒未完成 startForeground 导致
         * ForegroundServiceDidNotStartInTimeException 崩溃），由 UI 层引导授权。
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
            ContextCompat.startForegroundService(
                context,
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