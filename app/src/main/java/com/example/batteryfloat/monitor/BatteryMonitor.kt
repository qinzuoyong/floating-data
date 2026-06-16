package com.example.batteryfloat.monitor

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.batteryfloat.R
import com.example.batteryfloat.service.FloatingWindowService
import com.example.batteryfloat.shizuku.ShizukuHelper
import com.example.batteryfloat.view.FloatingWindowView
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * 电池监控器
 * - 协程每 2 秒轮询电池数据（温度 + 功耗）
 * - 温度：Shizuku sysfs 优先，BatteryManager 降级
 * - 功耗：BatteryManager.getIntProperty() 读取电压×电流
 * - 更新悬浮窗和前台通知
 */
class BatteryMonitor(
    private val context: Context,
    private val floatingView: FloatingWindowView
) {
    private val TAG = "BatteryMonitor"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    // ===== 缓存上次通知值，非显著变化时不更新通知 =====
    private var lastNotifiedTemp = -100f
    private var lastNotifiedPower = -100f

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        /** 温度变化超过此阈值才更新通知 */
        private const val TEMP_THRESHOLD = 0.1f
        /** 功耗变化超过此阈值才更新通知 */
        private const val POWER_THRESHOLD = 0.1f
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            while (isActive && isRunning) {
                fetchBatteryData()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
    }

    /** 获取温度+功耗双数据（一次注册 Intent，共享数据） */
    private suspend fun fetchBatteryData() {
        // 只注册一次 ACTION_BATTERY_CHANGED，温度/功耗共享同一份 intent 数据
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val temperature = if (ShizukuHelper.isRunning() && ShizukuHelper.hasPermission()) {
            readViaShizuku(batteryIntent)
        } else {
            getTemperatureFromIntent(batteryIntent)
        }
        val power = getPowerFromIntent(batteryIntent)
        if (temperature >= 0) {
            updateDisplay(temperature, power)
        }
    }

    /** Shizuku 读温度，失败降级 */
    private suspend fun readViaShizuku(fallbackIntent: android.content.Intent?): Float {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { }
            ShizukuHelper.readTemperature(object : ShizukuHelper.OnTemperatureListener {
                override fun onTemperature(celsius: Float) {
                    if (cont.isActive) cont.resumeWith(Result.success(celsius))
                }
                override fun onError(message: String) {
                    Log.w(TAG, "Shizuku 失败降级: $message")
                    // 降级时复用已注册的 intent，不再重复注册
                    if (cont.isActive) cont.resumeWith(
                        Result.success(getTemperatureFromIntent(fallbackIntent))
                    )
                }
            })
        }
    }

    /** 从已注册的 Intent 中提取电池温度，避免重复注册 */
    private fun getTemperatureFromIntent(intent: android.content.Intent?): Float {
        return try {
            if (intent != null) {
                val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (raw > 0) raw / 10f else -1f
            } else -1f
        } catch (e: Exception) {
            Log.e(TAG, "温度读取失败", e)
            -1f
        }
    }

    /**
     * 从已注册的 Intent 中提取电压，结合 BatteryManager API 计算功耗
     * P(W) = Voltage(mV) × |Current(μA)| / 1_000_000_000
     */
    private fun getPowerFromIntent(intent: android.content.Intent?): Float {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (currentNow == Int.MIN_VALUE) return -1f

            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (voltage > 0) {
                Math.abs(voltage.toDouble() * currentNow.toDouble() / 1_000_000_000.0).toFloat()
            } else -1f
        } catch (e: Exception) {
            Log.w(TAG, "功耗读取失败", e)
            -1f
        }
    }

    /** 温度变化超过此阈值才更新通知 */
    private suspend fun updateDisplay(celsius: Float, watts: Float) {
        withContext(Dispatchers.Main) {
            floatingView.updateTemperature(celsius)
            floatingView.updatePower(watts)
        }
        // 仅当温度或功耗有显著变化时更新通知，减少 I/O
        if (abs(celsius - lastNotifiedTemp) >= TEMP_THRESHOLD ||
            abs(watts - lastNotifiedPower) >= POWER_THRESHOLD
        ) {
            lastNotifiedTemp = celsius
            lastNotifiedPower = watts
            updateNotification(celsius, watts)
        }
    }

    private fun updateNotification(celsius: Float, watts: Float) {
        try {
            val powerStr = if (watts >= 0) String.format("%.1fW", watts) else "--W"
            val notification = NotificationCompat.Builder(context, FloatingWindowService.CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_title, String.format("%.1f", celsius)))
                .setContentText(powerStr)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(FloatingWindowService.NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败", e)
        }
    }
}