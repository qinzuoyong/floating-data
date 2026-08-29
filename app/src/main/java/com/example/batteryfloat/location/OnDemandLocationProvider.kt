package com.example.batteryfloat.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
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
 * 调用方需已持有定位权限（FINE 或 COARSE）。
 */
class OnDemandLocationProvider(private val context: Context) {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val executor = Executors.newSingleThreadExecutor()

    // ===== 常驻定位缓存（参照 Traccar/OwnTracks 常驻订阅模式） =====
    //
    // 精确位置不靠"请求时冷启动"，而是前台服务运行期间持续订阅 GPS/NETWORK
    // 周期更新维护出的缓存；loc-req 到达时直接秒回，不再临时定位。

    private var cache: LocationPayload? = null
    private val cacheLock = Any()
    private var continuous = false

    /** 常驻订阅回调：更优精度或缓存过期才覆盖，防止 NETWORK 粗定位劣化 GPS 级缓存 */
    private val continuousListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            synchronized(cacheLock) {
                val c = cache
                if (c == null || accuracyOf(location) <= c.accuracy ||
                    System.currentTimeMillis() - c.ts > CACHE_STALE_MS
                ) {
                    cache = LocationPayload(
                        lat = location.latitude,
                        lng = location.longitude,
                        ts = System.currentTimeMillis(),
                        accuracy = accuracyOf(location)
                    )
                }
            }
        }
    }

    /**
     * 开始常驻定位订阅（幂等；前台服务运行期间调用）
     *
     * @param gpsIntervalMs GPS 周期（默认 60s，现代 SoC 占空比调度下约 1~3%/小时）
     * @param networkIntervalMs NETWORK 周期（默认 30s，桥接粗位置）
     */
    @SuppressLint("MissingPermission")
    fun startContinuous(gpsIntervalMs: Long = 60_000L, networkIntervalMs: Long = 30_000L) {
        if (continuous) return
        continuous = true
        val intervals = mapOf(
            LocationManager.GPS_PROVIDER to gpsIntervalMs,
            LocationManager.NETWORK_PROVIDER to networkIntervalMs
        )
        for ((provider, interval) in intervals) {
            if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
            try {
                lm.requestLocationUpdates(provider, interval, 0f, executor, continuousListener)
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates($provider) failed", e)
            }
        }
    }

    /** 停止常驻订阅并清空缓存（服务销毁/重建前调用，防监听器泄漏） */
    fun stopContinuous() {
        if (!continuous) return
        continuous = false
        runCatching { lm.removeUpdates(continuousListener) }
        synchronized(cacheLock) { cache = null }
    }

    /**
     * 取新鲜缓存（默认 2 分钟内）——loc-req 即时应答用；
     * null 或精度差时调用方回退到 [currentLocationFlow] 并发定位流
     */
    fun freshCached(maxAgeMs: Long = 120_000L): LocationPayload? =
        synchronized(cacheLock) {
            cache?.takeIf { System.currentTimeMillis() - it.ts <= maxAgeMs }
        }

    /**
     * 并发定位流：NETWORK 与 GPS 同时请求，按 accuracy 由优到差发射更新。
     *
     * - NETWORK 先回（快、误差大）→ 先发射粗位置，蓝点快速出现
     * - GPS 后回（精确）→ 若 accuracy 更小则发射，蓝点更新到精确位置
     * - GPS 先回（室外）→ 直接精确，后到的 NETWORK 粗位置因 accuracy 更大被丢弃
     * - 全部 Provider 超时无果 → 兜底发射 lastKnown（有总比没有好）
     *
     * 调用方应收集整条流：每个发射都比上一个更精确（先粗后精多次回传依赖此语义）。
     *
     * @param timeoutMs 单 Provider 等待上限（GPS 冷启动较慢，默认给足精化窗口）
     */
    fun currentLocationFlow(timeoutMs: Long = 12_000L): Flow<LocationPayload> = channelFlow {
        val providers = enabledProviders()
        var emitted = false
        if (providers.isNotEmpty()) {
            var best: LocationPayload? = null
            val lock = Any()

            val jobs = providers.map { provider ->
                launch {
                    val loc = requestOnce(provider, timeoutMs) ?: return@launch
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

    /** 单个 Provider 的一次性定位请求（可取消；超时由协程取消触发） */
    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cancellation = CancellationSignal()
                cont.invokeOnCancellation { cancellation.cancel() }
                try {
                    lm.getCurrentLocation(provider, cancellation, executor) { loc: Location? ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation(" + provider + ") failed", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private companion object {
        const val TAG = "OnDemandLocation"

        /** 缓存判过期阈值：超过此时长的缓存不再视为新鲜，请求时重新定位 */
        const val CACHE_STALE_MS = 5 * 60_000L
    }
}
