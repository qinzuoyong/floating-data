package com.example.batteryfloat.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 电量百分比读取（零权限、零唤醒）
 *
 * 读系统粘性广播 ACTION_BATTERY_CHANGED：不需要任何权限、不注册常驻监听、
 * 不触发任何采样，取值即系统当前电量（与状态栏一致）。家人共享的"当前电量"
 * 由家人请求时按需现读一次，不做周期上报。
 */
object BatteryLevel {

    /** BatteryManager extra 缺失时的哨兵值 */
    private const val INVALID = -1

    /**
     * 当前电量百分比
     *
     * @param context 任意上下文（内部使用 applicationContext）
     * @return 0-100；取不到或数值异常时返回 null（调用方按"未知"处理，不展示脏数据）
     */
    fun currentPercent(context: Context): Int? {
        val intent: Intent = try {
            context.applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
        } catch (e: Exception) {
            // 个别 ROM 会拦截粘性广播读取；取不到即按"未知"处理，不向上抛
            return null
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, INVALID)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, INVALID)
        if (level == INVALID || scale <= 0) return null
        return Math.round(level * 100f / scale).coerceIn(0, 100)
    }
}
