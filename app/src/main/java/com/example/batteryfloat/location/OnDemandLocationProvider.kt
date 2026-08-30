package com.example.batteryfloat.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.example.batteryfloat.BuildConfig
import com.example.batteryfloat.p2p.LocationPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 按需定位提供器：取一次当前定位（不常驻 GNSS）
 *
 * 策略：NETWORK_PROVIDER 与 GPS_PROVIDER(含北斗/GNSS) 并发请求；
 * NETWORK 通常先返回（快、误差大）先出位置，GPS 后返回（精确）若 accuracy 更优则更新。
 * 配置了 AMAP_KEY 时，高德定位源（室内 WiFi 指纹 10~30m，室外融合 GNSS）并行加入
 * 同一套 accuracy 竞争；Key 缺失/异常/配额耗尽时静默跳过，退回纯系统定位。
 * 调用方需已持有定位权限（FINE 或 COARSE）。
 */
class OnDemandLocationProvider(private val context: Context) {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 高德定位源是否可用：密钥已配置且隐私合规双接口已调用（进程内只判一次）。
     * 不可用时调用方直接不发起高德请求，行为与未接入前一致；
     * 密钥无效/配额异常由后续定位回调的 errorCode 兜底（按无结果静默跳过）。
     */
    private val amapUsable: Boolean by lazy {
        if (BuildConfig.AMAP_KEY.isBlank()) return@lazy false
        runCatching {
            val appContext = context.applicationContext
            AMapLocationClient.updatePrivacyShow(appContext, true, true)
            AMapLocationClient.updatePrivacyAgree(appContext, true)
            AMapLocationClient.setApiKey(BuildConfig.AMAP_KEY)
            true
        }.onFailure { Log.w(TAG, "amap init failed", it) }.getOrDefault(false)
    }

    /**
     * 并发定位流：NETWORK 与 GPS 同时请求，按 accuracy 由优到差发射更新。
     *
     * - NETWORK 先回（快、误差大）→ 先发射粗位置，蓝点快速出现
     * - GPS 后回（精确）→ 若 accuracy 更小则发射，蓝点更新到精确位置
     * - GPS 先回（室外）→ 直接精确，后到的 NETWORK 粗位置因 accuracy 更大被丢弃
     * - 高德源（配置 AMAP_KEY 时）→ 室内 WiFi 指纹 10~30m 通常胜出，室外与 GPS 择优
     * - 全部 Provider 超时无果 → 兜底发射 lastKnown（有总比没有好）
     *
     * 调用方应收集整条流：每个发射都比上一个更精确（先粗后精多次回传依赖此语义）。
     *
     * @param timeoutMs 单 Provider 等待上限（GPS 冷启动较慢，默认给足精化窗口）
     */
    fun currentLocationFlow(timeoutMs: Long = 12_000L): Flow<LocationPayload> = channelFlow {
        val providers = enabledProviders()
        var emitted = false
        if (providers.isNotEmpty() || amapUsable) {
            var best: LocationPayload? = null
            val lock = Any()

            // 多源结果统一入口：更优精度才发射（先粗后精语义）
            fun submit(loc: Location?) {
                if (loc == null) return
                val payload = LocationPayload(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    // 以收到本次定位的时刻为准，避免 mock/异常定位源的旧时间戳
                    ts = System.currentTimeMillis(),
                    accuracy = accuracyOf(loc)
                )
                synchronized(lock) {
                    val b = best
                    if (b == null || payload.accuracy < b.accuracy) {
                        best = payload
                        emitted = true
                        trySend(payload)
                    }
                }
            }

            val jobs = providers.map { provider ->
                launch {
                    submit(requestOnce(provider, timeoutMs))
                }
            }.toMutableList()

            // 高德第三源：室内 WiFi 指纹 10~30m，室外融合 GNSS，与系统源同台竞争
            if (amapUsable) {
                jobs += launch {
                    submit(requestOnceAmap(timeoutMs))
                }
            }
            jobs.joinAll()
        }

        // 兜底：所有 Provider 超时/无果时用最近已知位置收尾，保证流至少发射一次
        if (!emitted) {
            for (provider in enabledProviders()) {
                val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
                trySend(
                    LocationPayload(
                        lat = last.latitude,
                        lng = last.longitude,
                        ts = System.currentTimeMillis(),
                        accuracy = accuracyOf(last)
                    )
                )
                break
            }
        }
    }

    /**
     * 取一次定位（并发流的第一个发射，快速响应；全失败回退 lastKnown）
     *
     * 注意：只取首个（通常是 NETWORK 粗定位）。需要精化结果的调用方
     * （如响应家人位置请求）应直接收集 [currentLocationFlow] 全部发射。
     *
     * @param timeoutMs 等待上限
     * @return 位置载荷；失败/无权限返回 null
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(timeoutMs: Long = 12_000L): LocationPayload? {
        val first = withTimeoutOrNull(timeoutMs) { currentLocationFlow(timeoutMs).first() }
        if (first != null) return first

        // 兜底：最近一次已知位置
        for (provider in enabledProviders()) {
            val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) {
                Log.i(TAG, "location fallback lastKnown provider=" + provider)
                return LocationPayload(
                    lat = last.latitude,
                    lng = last.longitude,
                    ts = System.currentTimeMillis(),
                    accuracy = accuracyOf(last)
                )
            }
        }
        return null
    }

    /** 已启用的定位 Provider（NETWORK 优先于 GPS，便于先拿到粗位置） */
    @SuppressLint("MissingPermission")
    private fun enabledProviders(): List<String> =
        listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    /**
     * 位置精度（米）：系统未报告精度时返回大值（视为未知，
     * 不参与"更优精度"竞争，避免无精度定位永久占优）
     */
    private fun accuracyOf(loc: Location): Float =
        if (loc.hasAccuracy()) loc.accuracy else 10_000f

    /**
     * 单个 Provider 的短窗口定位请求：只采纳新鲜定位（年龄≤[FRESH_MAX_AGE_MS]）。
     *
     * 实测 vivo 会把系统缓存的旧位置（分钟级、可能在数公里外）在 1~3ms 内回吐给
     * getCurrentLocation——本方法改用 requestLocationUpdates 短窗口监听并逐条过滤：
     * 陈旧缓存直接丢弃、继续等真实定位；超时仍无新鲜定位则返回 null
     * （由调用方的 lastKnown 兜底，宁缺毋假）。
     *
     * @param provider 定位源（GPS_PROVIDER / NETWORK_PROVIDER）
     * @param timeoutMs 等待上限，超时由协程取消触发
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val ageMs =
                            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
                        if (ageMs > FRESH_MAX_AGE_MS) return
                        // 新鲜定位：注销监听后返回（不再消耗该源）
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, executor, listener)
                } catch (e: Exception) {
                    Log.w(TAG, "requestOnce(" + provider + ") failed", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /**
     * 高德单次高精度定位：室内走 WiFi 指纹（10~30m），室外自动融合 GNSS。
     * AMapLocation 继承自 Location，与系统源共用 accuracy 竞争和新鲜度过滤；
     * errorCode!=0（密钥/配额/服务异常）按无结果处理，静默退回系统源。
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestOnceAmap(timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            Log.i(TAG, "amap request start")
            suspendCancellableCoroutine { cont ->
                val client = AMapLocationClient(context.applicationContext)
                val opt = AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = true
                    isOnceLocationLatest = true
                    isSensorEnable = false
                    isNeedAddress = false
                    httpTimeOut = timeoutMs
                }
                client.setLocationOption(opt)
                client.setLocationListener { loc ->
                    runCatching { client.stopLocation(); client.onDestroy() }
                    if (!cont.isActive) return@setLocationListener
                    if (loc == null || loc.errorCode != 0) {
                        Log.w(TAG, "amap locate failed code=" + (loc?.errorCode ?: -1) + " " + (loc?.errorInfo ?: ""))
                        cont.resume(null)
                        return@setLocationListener
                    }
                    // 高德SDK不设置 elapsedRealtimeNanos(默认0=开机时刻),须用 epoch 时间差判龄
                    val ageMs = if (loc.elapsedRealtimeNanos > 0L) {
                        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
                    } else {
                        System.currentTimeMillis() - loc.time
                    }
                    if (ageMs > FRESH_MAX_AGE_MS) {
                        Log.w(TAG, "amap result stale filtered age=${ageMs}ms")
                        cont.resume(null) // 高德源同样不采纳陈旧缓存
                        return@setLocationListener
                    }
                    Log.i(TAG, "amap result ok acc=" + accuracyOf(loc) + "m")
                    cont.resume(loc)
                }
                cont.invokeOnCancellation { runCatching { client.stopLocation(); client.onDestroy() } }
                client.startLocation()
            }
        }

    private companion object {
        const val TAG = "OnDemandLocation"

        /** 新鲜度阈值：位置年龄超过该值视为系统缓存（实测量产 vivo 会回吐分钟级旧缓存） */
        const val FRESH_MAX_AGE_MS = 30_000L
    }
}
