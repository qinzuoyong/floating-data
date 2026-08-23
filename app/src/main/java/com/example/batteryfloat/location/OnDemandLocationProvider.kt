package com.example.batteryfloat.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import com.example.batteryfloat.p2p.LocationPayload
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 按需定位提供器：取一次当前定位（不常驻 GPS）
 *
 * 策略：优先 LocationManager#getCurrentLocation（API 30+，快速返回，无 GMS 依赖，
 * 雷电模拟器可用），GPS/Network 双 Provider 串行尝试；全部失败回退 last known。
 * 调用方需已持有定位权限（FINE 或 COARSE）。
 */
class OnDemandLocationProvider(private val context: Context) {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 取一次定位
     *
     * @param timeoutMs 单 Provider 等待上限
     * @return 位置载荷；失败/无权限返回 null
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(timeoutMs: Long = 10_000L): LocationPayload? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        for (provider in providers) {
            val loc = requestOnce(provider, timeoutMs)
            if (loc != null) {
                Log.i(TAG, "location from provider=" + provider + " acc=" + loc.accuracy)
                return LocationPayload(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    ts = loc.time,
                    accuracy = loc.accuracy ?: 0f
                )
            }
        }

        // 兜底：最近一次已知位置
        for (provider in providers) {
            val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) {
                Log.i(TAG, "location fallback lastKnown provider=" + provider)
                return LocationPayload(
                    lat = last.latitude,
                    lng = last.longitude,
                    ts = last.time,
                    accuracy = last.accuracy ?: 0f
                )
            }
        }
        return null
    }

    /** 单个 Provider 的一次性定位请求（可取消） */
    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(provider: String, timeoutMs: Long): Location? =
        suspendCancellableCoroutine { cont ->
            val cancellation = CancellationSignal()
            cont.invokeOnCancellation { cancellation.cancel() }
            try {
                lm.getCurrentLocation(provider, cancellation, executor) { loc: Location? ->
                    if (!cont.isCompleted) cont.resume(loc)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation(" + provider + ") failed", e)
                if (!cont.isCompleted) cont.resume(null)
            }
            // 超时保护：请求挂起时由调用方 cancel，这里仅防死锁
            executor.execute {
                Thread.sleep(timeoutMs)
                if (!cont.isCompleted) {
                    cancellation.cancel()
                    cont.resume(null)
                }
            }
        }

    private companion object {
        const val TAG = "OnDemandLocation"
    }
}
