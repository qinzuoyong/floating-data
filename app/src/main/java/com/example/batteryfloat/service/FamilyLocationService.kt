package com.example.batteryfloat.service

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.batteryfloat.BuildConfig
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.R
import com.example.batteryfloat.diag.DiagLog
import com.example.batteryfloat.family.AlertPlace
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.family.GeofenceEvaluator
import com.example.batteryfloat.family.PlaceStore
import com.example.batteryfloat.notif.Notifs
import com.example.batteryfloat.p2p.LocationPayload
import com.example.batteryfloat.p2p.SignalClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 家人位置共享后台服务（纯信令中继，无 WebRTC；2026-09 起不再前台运行）
 *
 * 职责：常驻后台保持信令连接（WebSocket）→ 按需响应家人位置请求
 * （一次性定位 → 信令回传）；收到家人位置时写入 [FamilyStore]，Compose UI 实时观察。
 *
 * 保活：本服务是普通后台服务（无专属前台通知、不占用前台服务名额）。
 * - 平台不会停它：系统只在「UID 沦为 idle」时才停其中的后台服务，而 UID 是否 idle
 *   取决于其进程 procstate 是否属后台类；无障碍保活（[KeepAliveAccessibilityService]）
 *   由 system_server 以 BIND_FOREGROUND_SERVICE_WHILE_AWAKE 绑定本进程，使其处于
 *   BOUND_FOREGROUND_SERVICE / IMPORTANT_FOREGROUND，均低于后台阈值，故 UID 不进入
 *   idle 倒计时。电池优化白名单（AdbAutoGrant 自动加白）是第二层保障。
 * - 进程/设备重建后的恢复：开机广播 + 无障碍保活通道按 [shouldAutoRestore] 恢复，
 *   另有 START_STICKY 兜底；[PrefsKeys.FAMILY_WAS_RUNNING] 只在用户主动停止时清除。
 * - 既未开无障碍、也无悬浮窗前台服务时，进程属 cached 类，可被系统/厂商回收。
 *
 * 权限前置：需已授予定位权限（FINE/COARSE），否则降级提示。
 *
 * 信令分发与应答（[FamilySignalHandler]）已按职责抽到独立文件（2026-09，纯搬移零行为变化），
 * 本类保留服务启停、前台通知与自愈入口。
 */
class FamilyLocationService : Service() {

    internal val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 信令分发与应答（信令报文的受理、去抖、白名单、载荷校验） */
    private val signalHandler = FamilySignalHandler(this)

    internal var signal: SignalClient? = null

    /** 信令状态收集任务:重建通道前取消,避免旧实例的 collector 在服务存活期内累积泄漏 */
    private var stateCollectJob: Job? = null

    /** 地点提醒存储（地点列表/总开关/判定状态） */
    private val placeStore: PlaceStore get() = PlaceStore.get(this)

    init {
        // 位置入库后交到达/离开判定（判定与提醒只在转换时发生，见 onMemberLocation）
        signalHandler.onLocation = ::onMemberLocation
    }

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
                // 用户主动停止:清除「应在运行」标记,此后开机与进程重建不再自动恢复
                // (系统回收进程不会走 ACTION_STOP,故不会误清标记)
                markRunning(this, false)
                // 停止共享后提醒无从判定：撤销轮询任务，不继续唤醒请求家人位置
                cancelAlertPoll(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REQUEST_LOCATION -> {
                val uid = intent.getStringExtra(EXTRA_UID) ?: ""
                if (uid.isNotBlank()) signalHandler.requestMemberLocation(uid)
                // 通道未建立（如用户已停止共享后从地图页冷启动本服务）：提示已上屏（静态
                // StateFlow，服务销毁后 UI 仍可读），随即退出——避免 isRunning=true 的僵尸
                // 实例短路无障碍恢复路径（tryRestoreFamilyService 判 isRunning 即返回）并让 UI 状态失真
                if (signal == null) stopSelf()
                return START_STICKY
            }
            ACTION_REQUEST_STATUS -> {
                val uid = intent.getStringExtra(EXTRA_UID) ?: ""
                if (uid.isNotBlank()) signalHandler.requestMemberStatus(uid)
                // 守卫同位置请求：通道未建立时不留僵尸实例
                if (signal == null) stopSelf()
                return START_STICKY
            }
            ACTION_APPROVE_JOIN -> {
                val uid = intent.getStringExtra(EXTRA_JOIN_UID) ?: ""
                if (uid.isNotBlank()) signal?.sendJoinApprove(uid)
                if (signal == null) stopSelf()
                return START_STICKY
            }
            ACTION_REJECT_JOIN -> {
                val uid = intent.getStringExtra(EXTRA_JOIN_UID) ?: ""
                if (uid.isNotBlank()) signal?.sendJoinReject(uid)
                if (signal == null) stopSelf()
                return START_STICKY
            }
            ACTION_ALERT_POLL -> {
                // 通道未建立（多为进程被回收后由本告警拉起）：按用户意图重建
                if (signal == null && shouldAutoRestore(this)) setup()
                if (signal == null) {
                    cancelAlertPoll(this)
                    stopSelf()
                } else {
                    syncAlertPoll(this)
                    pollAlertPlaces()
                }
                return START_STICKY
            }
            else -> {
                // ACTION_START（含重复点击）= 全量重建连接（家庭码可能已变更）
                setup()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        stateCollectJob?.cancel()
        stateCollectJob = null
        // 收集器已取消，断开时状态流不会再更新：复位为 Idle，
        // 否则 UI 的连接状态滞留在"已连接"，与服务实际存活状态不符
        _connection.value = SignalClient.State.Idle
        signalHandler.clearRequestRecords()
        signal?.disconnect()
        signalHandler.closeProvider()
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
        signalHandler.attach(s)
        // 服务重复 start 重建通道前,先释放旧实例的定位线程池
        signalHandler.openProvider()

        val code = s.familyCode()
        if (code.isBlank()) {
            Log.i(TAG, "未加入家庭，仅驻留前台")
            return
        }
        // 记录「应在运行」:进程/设备重建后由开机广播与无障碍保活通道据此恢复。
        // 只在用户主动停止时清除(见 ACTION_STOP),系统回收进程同理不写 false
        markRunning(this, true)

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

        // 备用信令地址（可选）：主地址不可达时自动切换，主恢复后自动切回。
        // 非法/未配置则传空串 → SignalClient 单端点，行为与改造前完全一致。
        val backupUrl = BuildConfig.SIGNAL_URL_BACKUP
            .takeIf { it.startsWith("ws://") || it.startsWith("wss://") } ?: ""

        val sig = SignalClient(signalUrl, backupUrl).also {
            it.onMessage = signalHandler::handleSignal
            // 连接事件落盘（vivo 等机型屏蔽应用 logcat 时的唯一时序来源；写入口已脱敏）
            it.diagLogger = { line -> DiagLog.append(this, line) }
        }
        signal = sig

        // 重建通道前先取消上一条状态收集任务,否则每次 setup 都会留下一个旧实例的 collector
        stateCollectJob?.cancel()
        stateCollectJob = workScope.launch {
            sig.state.collect { _connection.value = it }
        }
        sig.connect(code, s.myUid(), s.myName())
        // 不把家庭码拼进日志：logcat 明文可被 adb 读到，与「敏感值零输出」约定冲突；
        // 连接时序另有 DiagLog 落盘（写入口已按家庭码/uid 定点脱敏）
        Log.i(TAG, "signal connecting")
        // 通道就绪后同步地点提醒的定时轮询（总开关关闭时本调用即撤销任务）
        syncAlertPoll(this)
    }

    // ===== 家人到达/离开提醒 =====

    /**
     * 位置入库后的判定入口（信令线程/协程回调）
     *
     * 逐地点判定：命中到达/离开转换才发通知（首次样本只建立基线、滞回区内不动、
     * 去重窗口内不重复提醒——规则见 [GeofenceEvaluator]）。判定状态逐条落盘，重启延续。
     */
    private fun onMemberLocation(uid: String, loc: LocationPayload) {
        if (!placeStore.alertsEnabled()) return
        val places = placeStore.activePlaces()
        if (places.isEmpty()) return
        // 只对名册内成员判定，与 FamilyStore.updateLocation 的准入一致（不认幽灵成员）
        val member = FamilyStore.get(this).members.value[uid] ?: return
        val now = System.currentTimeMillis()
        for (place in places) {
            if (place.watchUids.isNotEmpty() && uid !in place.watchUids) continue
            val previous = placeStore.stateFor(uid, place.id)
            val distance = GeofenceEvaluator.distanceMeters(loc.lat, loc.lng, place.lat, place.lng)
            when (val decision = GeofenceEvaluator.evaluate(
                previous = previous,
                radiusMeters = place.radiusMeters,
                distanceMeters = distance,
                accuracyMeters = loc.accuracy,
                sampleTs = loc.ts,
                nowMs = now
            )) {
                GeofenceEvaluator.Decision.Discarded ->
                    Log.i(TAG, "地点提醒样本丢弃 place=" + place.id)
                is GeofenceEvaluator.Decision.Quiet -> {
                    // 状态有变化才落盘（首次基线 / 状态翻转后的去重抑制）
                    if (previous != decision.state) placeStore.putState(uid, place.id, decision.state)
                }
                is GeofenceEvaluator.Decision.Alert -> {
                    placeStore.putState(uid, place.id, decision.state)
                    val title = getString(
                        if (decision.entered) R.string.family_alert_arrive_title
                        else R.string.family_alert_leave_title,
                        member.displayName,
                        place.name
                    )
                    notifyPlaceAlert(uid, place, title, now)
                }
            }
        }
    }

    /** 发一条到达/离开提醒（同一「成员 × 地点」固定通知 id：同地点的后续提醒覆盖上一条） */
    private fun notifyPlaceAlert(uid: String, place: AlertPlace, title: String, nowMs: Long) {
        val text = getString(
            R.string.family_alert_time_text,
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(nowMs))
        )
        val notifyId = Notifs.familyAlertId(uid, place.id)
        runCatching {
            // 渠道幂等创建：本服务不再前台常驻，渠道可能还没建过
            Notifs.ensureChannels(this)
            getSystemService(android.app.NotificationManager::class.java)
                .notify(notifyId, Notifs.familyPlaceAlert(this, title, text, notifyId))
        }.onFailure { Log.w(TAG, "地点提醒通知失败", it) }
    }

    /**
     * 向被监视成员发出一次位置请求
     *
     * 目标集合 = 各启用地点 watchUids 的并集（watchUids 为空的地点视为监视全部在线成员）。
     * 记账复用 [FamilySignalHandler.requestMemberLocation]：回包仍须通过 loc-res 准入校验。
     */
    private fun pollAlertPlaces() {
        if (!placeStore.alertsEnabled()) return
        val places = placeStore.activePlaces()
        if (places.isEmpty()) return
        val online = FamilyStore.get(this).members.value.values.filter { it.online }
        if (online.isEmpty()) return
        val targets = LinkedHashSet<String>()
        for (place in places) {
            if (place.watchUids.isEmpty()) {
                online.forEach { targets += it.uid }
            } else {
                online.filter { it.uid in place.watchUids }.forEach { targets += it.uid }
            }
        }
        for (uid in targets) signalHandler.requestMemberLocation(uid)
    }

    companion object {
        internal const val TAG = "FamilyLocationService"

        /** 服务是否在运行（UI 查询用；替代已废弃的 ActivityManager.getRunningServices） */
        @Volatile
        var isRunning = false
            private set

        const val ACTION_START = "com.yongge.batteryfloat.action.FAMILY_START"
        const val ACTION_STOP = "com.yongge.batteryfloat.action.FAMILY_STOP"
        const val ACTION_REQUEST_LOCATION = "com.yongge.batteryfloat.action.FAMILY_REQ_LOC"
        const val ACTION_REQUEST_STATUS = "com.yongge.batteryfloat.action.FAMILY_REQ_STATUS"
        const val ACTION_APPROVE_JOIN = "com.yongge.batteryfloat.action.FAMILY_APPROVE_JOIN"
        const val ACTION_REJECT_JOIN = "com.yongge.batteryfloat.action.FAMILY_REJECT_JOIN"
        /** 地点提醒的定时轮询（由 AlarmManager 的 PendingIntent 投递，见 [syncAlertPoll]） */
        const val ACTION_ALERT_POLL = "com.yongge.batteryfloat.action.FAMILY_ALERT_POLL"
        const val EXTRA_UID = "uid"
        const val EXTRA_JOIN_UID = "join_uid"

        /** 轮询 PendingIntent 的请求码（登记与撤销必须同一个） */
        private const val REQ_ALERT_POLL = 4001

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
        internal fun postNotice(text: String) {
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
            if (!hasLocationPermission(context)) {
                Log.w(TAG, "start skipped: 缺少定位权限")
                return
            }
            context.startService(
                Intent(context, FamilyLocationService::class.java).setAction(ACTION_START)
            )
        }

        /**
         * 记录「家人位置共享应在运行」（语义同 [PrefsKeys.FLOATING_WAS_RUNNING]）
         *
         * 只在用户主动开启/停止时写入；onDestroy 不写 false——系统回收进程同样会走
         * onDestroy，写 false 会让开机自启与无障碍保活通道失去恢复依据。
         * @param running true=已加入家庭并启动（见 setup），false=用户主动停止（见 ACTION_STOP）
         */
        fun markRunning(context: Context, running: Boolean) {
            context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PrefsKeys.FAMILY_WAS_RUNNING, running).apply()
        }

        /**
         * 是否应在进程/设备重建后自动恢复家人位置共享
         *
         * 门控：开机自启动开 + 上次在运行 + 已加入家庭 + 已授定位权限。
         * 供开机广播（[com.example.batteryfloat.receiver.BootReceiver]）与无障碍保活通道
         * （[KeepAliveAccessibilityService]，system_server 绑定拉起进程）共用。
         */
        fun shouldAutoRestore(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PrefsKeys.BOOT_AUTO_START, true)) return false
            if (!prefs.getBoolean(PrefsKeys.FAMILY_WAS_RUNNING, false)) return false
            if ((prefs.getString(PrefsKeys.FAMILY_CODE, "") ?: "").isBlank()) return false
            return hasLocationPermission(context)
        }

        /** 定位权限就绪检查（启动前置与恢复门控共用同一判定，避免两处漂移） */
        private fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

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

        /** 请求指定成员的当前状态（电量）（家人页进入时自动刷新 / 成员卡手动刷新） */
        fun requestStatus(context: Context, uid: String) {
            context.startService(
                Intent(context, FamilyLocationService::class.java)
                    .setAction(ACTION_REQUEST_STATUS)
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

        /**
         * 同步地点提醒的定时轮询（幂等；总开关/地点/频率变更后由 UI 调用）
         *
         * 用**非精确**一次性任务 [AlarmManager.setAndAllowWhileIdle]，每次触发后由服务续下一次
         * （见 onStartCommand → ACTION_ALERT_POLL）：不用 setRepeating 是因为后者的周期任务
         * 在 Doze 下会被整体推迟到维护窗口，长静止期将完全停止判定；本实现不承诺精确时刻
         * （判定本就是分钟级采样），但能穿透 Doze。总开关关闭或无启用地点时只撤销任务。
         *
         * @param context 任意 Context（用应用 Context 的 AlarmManager）
         */
        fun syncAlertPoll(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val store = PlaceStore.get(context)
            if (am == null || !store.alertsEnabled() || store.activePlaces().isEmpty()) {
                cancelAlertPoll(context)
                return
            }
            val pi = alertPollIntent(context)
            am.cancel(pi) // 先撤旧：频率变更后立即按新间隔计时
            val intervalMs = store.intervalMinutes() * 60_000L
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs,
                pi
            )
        }

        /** 撤销地点提醒的定时轮询（用户停止共享 / 关闭总开关 / 无启用地点） */
        fun cancelAlertPoll(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(alertPollIntent(context))
        }

        /** 轮询用的 PendingIntent（getService：直接投递本服务的 [ACTION_ALERT_POLL]） */
        private fun alertPollIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context,
                REQ_ALERT_POLL,
                Intent(context, FamilyLocationService::class.java).setAction(ACTION_ALERT_POLL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}