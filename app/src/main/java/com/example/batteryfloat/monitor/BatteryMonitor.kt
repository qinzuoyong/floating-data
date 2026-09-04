package com.example.batteryfloat.monitor

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.example.batteryfloat.R
import com.example.batteryfloat.data.BatteryProvider
import com.example.batteryfloat.notif.Notifs
import com.example.batteryfloat.service.FloatingWindowService
import com.example.batteryfloat.view.FloatingWindowView
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 电池监控器（消费侧）
 * - 协程每 2 秒经 [BatteryProvider] 采样电池数据（温度 + 功耗）
 * - 更新悬浮窗和前台通知
 * - 采集实现下沉到 data 包（基础档 BasicBatteryProvider，增强档后续接入）
 */
class BatteryMonitor(
    private val context: Context,
    private val floatingView: FloatingWindowView,
    private val provider: BatteryProvider
) {
    private val TAG = "BatteryMonitor"
    // 使用 SupervisorJob 配合 CoroutineName，便于调试和取消管理
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("BatteryMonitor"))
    private val isRunning = AtomicBoolean(false)

    // ===== 缓存上次通知值，非显著变化时不更新通知 =====
    // lastNotifiedPower 用 -Infinity 而非 NaN 作哨兵：
    // NaN 会污染 abs(watts - NaN) 比较（恒为 NaN→false）并回写缓存，
    // 导致功耗从"不可用"转为有效时通知不更新。-Infinity 与有限值比较为 true，能正确首帧触发。
    private var lastNotifiedTemp = -100f
    private var lastNotifiedPower = Float.NEGATIVE_INFINITY

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        /** 温度变化超过此阈值才更新通知（悬浮窗文本仍每 2 秒刷新，不受此阈值影响） */
        private const val TEMP_THRESHOLD = 0.5f
        /** 功耗变化超过此阈值才更新通知 */
        private const val POWER_THRESHOLD = 0.5f
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        // stop() 会 cancel 协程作用域；重新 start 前重建，避免协程在已取消作用域中静默失效
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("BatteryMonitor"))
        scope.launch {
            while (isActive && isRunning.get()) {
                try {
                    fetchBatteryData()
                } catch (e: Exception) {
                    Log.e(TAG, "电池数据获取异常", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        // 优雅关闭协程，等待正在执行的任务完成
        scope.cancel()
    }

    /** 经数据源采样一次，温度有效时驱动展示（语义与原实现一致） */
    private suspend fun fetchBatteryData() {
        val sample = provider.sample() ?: return
        if (sample.batteryTempC >= 0) {
            updateDisplay(sample.batteryTempC, sample.powerW)
        }
    }

    /** 温度变化超过此阈值才更新通知 */
    private suspend fun updateDisplay(celsius: Float, watts: Float) {
        withContext(Dispatchers.Main) {
            floatingView.updateTemperature(celsius)
            floatingView.updatePower(watts)
        }
        // 仅当温度或功耗有显著变化时更新通知，减少 I/O
        // 功耗为 NaN 时不参与比较、不回写缓存，避免 NaN 污染 lastNotifiedPower
        val tempChanged = kotlin.math.abs(celsius - lastNotifiedTemp) >= TEMP_THRESHOLD
        val powerChanged = watts.isFinite() &&
                kotlin.math.abs(watts - lastNotifiedPower) >= POWER_THRESHOLD
        if (tempChanged || powerChanged) {
            lastNotifiedTemp = celsius
            if (watts.isFinite()) lastNotifiedPower = watts
            updateNotification(celsius, watts)
        }
    }

    private fun updateNotification(celsius: Float, watts: Float) {
        try {
            // 符号格式与悬浮窗一致(%+.1fW):正=充电,负=放电;温度与功耗合并到同一标题行(同字号)
            val powerStr = if (watts.isFinite()) String.format(Locale.US, "%+.1fW", watts) else "--W"
            // 正文为静态说明,点击回到应用的跳转保留
            val notification = Notifs.floatingUpdate(
                context,
                context.getString(
                    R.string.notification_title,
                    String.format(Locale.US, "%.1f", celsius),
                    powerStr
                ),
                context.getString(R.string.notification_foreground_text)
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(FloatingWindowService.NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败", e)
        }
    }
}