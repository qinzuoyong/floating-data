package com.example.batteryfloat.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * 基础电池数据源(公开 API 档)
 * - ACTION_BATTERY_CHANGED 粘性广播取温度/电压/充电状态
 * - BatteryManager.BATTERY_PROPERTY_CURRENT_NOW 取瞬时电流
 * - 功耗 P(W) = Voltage(mV) × |Current(μA)| / 1e9,用充电状态决定符号
 *   (不同制造商 currentNow 符号约定不一致,充电→正/放电→负)
 *
 * 逻辑自 BatteryMonitor 原样迁入,行为零变化;
 * 后续增强档(PrivBatteryProvider)以其为逐字段回退
 */
class BasicBatteryProvider(context: Context) : BatteryProvider {

    private val appContext = context.applicationContext
    private val batteryManager =
        appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    /** 缓存 IntentFilter 对象，避免每次采样创建新对象 */
    private val batteryIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    override suspend fun sample(): BatterySample? {
        return try {
            // 使用缓存的 IntentFilter，避免每次创建新对象
            val batteryIntent = appContext.registerReceiver(null, batteryIntentFilter)
            BatterySample(
                batteryTempC = getTemperatureFromIntent(batteryIntent),
                powerW = getPowerFromIntent(batteryIntent),
                voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
                charging = isCharging(batteryIntent),
            )
        } catch (e: Exception) {
            Log.w(TAG, "电池采样失败", e)
            null
        }
    }

    /** 从已注册的 Intent 中提取电池温度 */
    private fun getTemperatureFromIntent(intent: Intent?): Float {
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
     * 公式: P(W) = Voltage(mV) × |Current(μA)| / 1,000,000,000
     * 充电 → 正值，放电 → 负值
     */
    private fun getPowerFromIntent(intent: Intent?): Float {
        return try {
            if (intent == null) return Float.NaN
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (voltage <= 0) return Float.NaN
            val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (currentNow == Int.MIN_VALUE) return Float.NaN
            // 使用电池状态决定符号，而非依赖 currentNow 符号（不同制造商约定不一致）
            val rawPower = (voltage.toFloat() * kotlin.math.abs(currentNow.toFloat())) / 1_000_000_000f
            when (isCharging(intent)) {
                true -> rawPower        // 充电 → 正值
                false -> -rawPower      // 放电 → 负值
                null -> rawPower        // 未知 → 正值
            }
        } catch (e: Exception) {
            Log.w(TAG, "功耗读取失败", e)
            Float.NaN
        }
    }

    /**
     * 判断当前是否正在充电
     * @return true 表示充电中，null 表示无法判断
     */
    private fun isCharging(intent: Intent?): Boolean? {
        if (intent == null) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
    }

    companion object {
        private const val TAG = "BasicBatteryProvider"
    }
}
