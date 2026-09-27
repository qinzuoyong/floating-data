package com.example.batteryfloat.service

import android.util.Log
import com.example.batteryfloat.R
import com.example.batteryfloat.data.BatteryLevel
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.location.OnDemandLocationProvider
import com.example.batteryfloat.p2p.LocationPayload
import com.example.batteryfloat.p2p.SignalMessage
import com.example.batteryfloat.p2p.SignalTypes
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 家人信令分发与应答（自 [FamilyLocationService] 按职责搬移，行为不变）
 *
 * 职责：
 * - 远端报文入口与路由（[handleSignal] / [dispatchSignal]）：名册重建、上下线、加入审核、
 *   位置与状态中继、错误上屏；远端数据异常全部在此收敛，绝不冒泡到线程级；
 * - 家人发来的 loc-req / stat-req 受理：隐私开关、去抖窗口、应答方记账；
 * - 收到的 loc-res / stat-res 准入校验：只接受"本机曾请求过"的成员在有效期内的应答；
 * - 定位提供者生命周期与按需应答（[openProvider] / [closeProvider]）。
 *
 * 服务启停、前台通知与自愈入口仍留在 [FamilyLocationService] 主服务中。
 */
internal class FamilySignalHandler(private val host: FamilyLocationService) {

    private val gson = Gson()

    private var store: FamilyStore? = null

    /** 按需定位提供者：服务 setup 时重建、onDestroy 时释放，loc-req 应答共用同一实例 */
    private var provider: OnDemandLocationProvider? = null

    /**
     * 本机已发出/已受理的位置请求记录(uid → 受理时间戳)。
     * 仅接受"曾请求过位置"的成员在有效期内回传的 loc-res,防止房间内任意成员
     * 伪造位置或注入幽灵成员;重复请求的去重见 [incomingLocReqAt]。
     */
    private val requestedLocations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 家人发来的位置请求去重窗口(uid → 上次受理时间戳)。
     * 与 [requestedLocations] 分开记:后者含"本机主动请求对方"的记录,
     * 若共用一张表,本机刚请求过对方位置时,对方反向发来的请求会被误判成
     * "重复请求"而静默丢弃(双方先后打开彼此地图页的真实场景)。
     */
    private val incomingLocReqAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 本机已发出的状态(电量)请求记录(uid → 发出时间戳)。
     * 与 [requestedLocations] 同理：只接受"曾请求过状态"的成员在有效期内回传的 stat-res，
     * 防止房间内任意成员伪造电量或注入幽灵成员。
     */
    private val requestedStatus = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 家人发来的状态请求去重窗口(uid → 上次受理时间戳)，防家人连点 */
    private val incomingStatReqAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 挂上本地存储（成员名册/位置/电量的落点）；服务 setup 时调用 */
    fun attach(store: FamilyStore) {
        this.store = store
    }

    /**
     * 位置入库后的回调（家人到达/离开提醒的判定入口）
     *
     * 只在通过准入校验、真正写入 [FamilyStore] 之后触发——提醒与"家人列表里的位置"
     * 看到的是同一份数据，不会出现"列表没更新却报了到达"。
     */
    var onLocation: ((String, LocationPayload) -> Unit)? = null

    /**
     * 创建(或重建)定位提供者
     *
     * 服务重复 start 重建通道前必须先释放旧实例的定位线程池。
     */
    fun openProvider() {
        provider?.close()
        provider = OnDemandLocationProvider(host)
    }

    /** 释放定位提供者（服务 onDestroy） */
    fun closeProvider() {
        provider?.close()
    }

    /** 清空请求记账（服务 onDestroy；收集器已取消，残留记录不会再被使用） */
    fun clearRequestRecords() {
        requestedLocations.clear()
        incomingLocReqAt.clear()
        requestedStatus.clear()
        incomingStatReqAt.clear()
    }

    /** 请求指定成员的位置（UI 调用入口）；未连接时上屏提示，不再静默丢弃 */
    fun requestMemberLocation(uid: String) {
        val sent = host.signal?.sendLocReq(uid) == true
        if (!sent) {
            FamilyLocationService.postNotice(host.getString(R.string.family_error_not_connected))
            return
        }
        // 记录"已请求"：后续只接受该成员在有效期内的应答（见 handleSignal → LOC_RES）
        requestedLocations[uid] = System.currentTimeMillis()
        // 顺带回收过期记录,避免长时间运行后无界增长
        val expireBefore = System.currentTimeMillis() - LOC_REQ_TTL_MS
        requestedLocations.entries.removeAll { it.value < expireBefore }
        incomingLocReqAt.entries.removeAll { it.value < expireBefore }
    }

    /**
     * 请求指定成员的状态（电量）（UI 调用入口）；未连接时上屏提示，不再静默丢弃
     *
     * 与位置请求同一套记账：记下"已请求"用于校验回包的来源与时序。
     */
    fun requestMemberStatus(uid: String) {
        val sent = host.signal?.sendStatReq(uid) == true
        if (!sent) {
            FamilyLocationService.postNotice(host.getString(R.string.family_error_not_connected))
            return
        }
        requestedStatus[uid] = System.currentTimeMillis()
        // 顺带回收过期记录,避免长时间运行后无界增长
        val expireBefore = System.currentTimeMillis() - LOC_REQ_TTL_MS
        requestedStatus.entries.removeAll { it.value < expireBefore }
        incomingStatReqAt.entries.removeAll { it.value < expireBefore }
    }

    /**
     * 信令消息入口：任何远端数据异常都在此收敛，绝不冒泡到线程级。
     * 远端报文（含 payload）完全不可信，反序列化/字段异常必须降级为"丢弃本条 + 留痕"，
     * 否则会沿 Dispatchers.Main 的协程抛出并令进程崩溃（可被房间内任意成员远程触发）。
     */
    fun handleSignal(msg: SignalMessage) {
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
                    // 名册即家庭成员全集：不在册成员的地点判定状态（uid|placeId）一并丢弃，
                    // 否则会残留累积、并在该 uid 回来时按陈旧基线判定一次
                    host.pruneAlertStates(roster.map { it.uid }.toSet())
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
                // 同一成员在合并窗口内的重复请求直接忽略：防止家人连点把 GNSS 拉成持续采集（耗电）。
                // 去重只看"对方发来的请求"记录（incomingLocReqAt），不能混入本机主动请求
                // 对方的记录，否则双方先后请求彼此位置时，后到的反向请求会被误丢弃
                val now = System.currentTimeMillis()
                val lastIncoming = incomingLocReqAt[from]
                if (lastIncoming != null && now - lastIncoming < LOC_REQ_COOLDOWN_MS) {
                    Log.i(TAG, "ignore duplicated loc-req from " + from)
                    return
                }
                incomingLocReqAt[from] = now
                // 受理后记入 requestedLocations：对该成员回传的 loc-res 在有效期内放行
                requestedLocations[from] = now
                host.workScope.launch {
                    // 先粗后精多次回传：NETWORK 粗定位先到先发（对方几秒内出图），
                    // GPS 更优结果到达后再次回传自动覆盖（服务器中继与存储均幂等）
                    var sent = 0
                    withContext(Dispatchers.Default) {
                        p.currentLocationFlow().collect { loc ->
                            sent++
                            host.signal?.sendLocRes(from, loc)
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
                onLocation?.invoke(from, loc)
            }

            SignalTypes.STAT_REQ -> {
                val from = msg.from ?: return
                // 与位置请求共用同一个隐私开关：关闭后位置与电量都不应答
                if (!s.allowLocReq()) {
                    Log.i(TAG, "ignore stat-req from " + from + " (privacy off)")
                    return
                }
                // 同一成员短窗口内的重复请求直接忽略（电量读取几乎无成本，仅防连点刷屏）
                val now = System.currentTimeMillis()
                val lastIncoming = incomingStatReqAt[from]
                if (lastIncoming != null && now - lastIncoming < STAT_REQ_COOLDOWN_MS) {
                    Log.i(TAG, "ignore duplicated stat-req from " + from)
                    return
                }
                incomingStatReqAt[from] = now
                // 受理后记入 requestedStatus：对该成员回传的 stat-res 在有效期内放行
                requestedStatus[from] = now
                val battery = BatteryLevel.currentPercent(host)
                if (battery == null) {
                    Log.w(TAG, "battery level unavailable, cannot answer " + from)
                    return
                }
                // 现读即回：读的是系统粘性广播，无定位、无采样成本，故一次回传即可
                host.signal?.sendStatRes(
                    from,
                    com.example.batteryfloat.p2p.StatusPayload(battery, now)
                )
            }

            SignalTypes.STAT_RES -> {
                val from = msg.from ?: return
                // 只接受本机曾请求过、且在有效期内的成员应答（同 loc-res 的防伪造约束）
                val requestedAt = requestedStatus[from]
                if (requestedAt == null) {
                    Log.w(TAG, "drop unsolicited stat-res from " + from)
                    return
                }
                if (System.currentTimeMillis() - requestedAt > LOC_RES_TTL_MS) {
                    Log.w(TAG, "drop expired stat-res from " + from)
                    return
                }
                val status = msg.payload?.let {
                    runCatching {
                        gson.fromJson(it, com.example.batteryfloat.p2p.StatusPayload::class.java)
                    }.getOrNull()
                } ?: return
                if (!isPlausibleStatus(status)) {
                    Log.w(TAG, "drop invalid stat-res payload from " + from)
                    return
                }
                s.updateBattery(from, status.battery, status.ts)
            }

            SignalTypes.ERROR -> {
                Log.w(TAG, "signal error: " + (msg.message ?: msg.code))
                // 服务器回执上屏：目标离线等错误此前只打日志，按钮像"没反应"
                FamilyLocationService.postNotice(
                    when (msg.code) {
                        "offline" -> host.getString(R.string.family_error_offline)
                        else -> host.getString(
                            R.string.family_error_generic,
                            msg.message ?: msg.code ?: ""
                        )
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

    /**
     * 远端状态载荷合理性校验：电量越界、时间戳非法一律拒绝。
     *
     * 与 [isPlausibleLocation] 同一目的——远端报文完全不可信；且 Gson 对缺失数值字段
     * 会填 0，故电量与时间戳都必须显式校验（时间戳缺失的包按 0 拒绝，fail-closed）。
     */
    private fun isPlausibleStatus(status: com.example.batteryfloat.p2p.StatusPayload): Boolean {
        if (status.battery < 0 || status.battery > 100) return false
        if (status.ts <= 0L) return false
        return status.ts <= System.currentTimeMillis() + 5 * 60_000L
    }

    private companion object {
        /** 沿用主服务的日志 TAG：logcat 按 FamilyLocationService 过滤始终有效 */
        private val TAG = FamilyLocationService.TAG

        /** 同一成员重复位置请求的合并窗口（忽略窗口内的重复请求，保护 GNSS 采集功耗） */
        private const val LOC_REQ_COOLDOWN_MS = 20_000L

        /** 已记录请求的保留时长（超过即回收，防长时间运行时 map 无界增长） */
        private const val LOC_REQ_TTL_MS = 30 * 60_000L

        /** 位置应答有效期：超过该时长才到达的应答视为过期并丢弃 */
        private const val LOC_RES_TTL_MS = 5 * 60_000L

        /** 同一成员重复状态（电量）请求的合并窗口：电量读取几乎无成本，仅防连点刷屏 */
        private const val STAT_REQ_COOLDOWN_MS = 5_000L
    }
}
