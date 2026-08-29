package com.example.batteryfloat.data

import android.content.Context
import com.example.batteryfloat.adb.PrivShell

/**
 * 高精度电池数据源(ADB shell 特权档)
 *
 * - 仅当特权通道已连接时走 shell;其余状态(未启用/断开/重连中)整体委托内部基础档,
 *   悬浮窗永不断流,通道恢复后下个采样周期自动切回
 * - 每次采样合并为一条 shell 命令,逐行带标签解析,失败的行仅影响该字段:
 *   - batteryTempC:battery/temp 直读(瞬时值,采样率高于粘性广播)→ 回退基础档
 *   - powerW:battery/power_now(μW)直读幅值,符号由充电状态决定
 *     (不同厂商内核符号约定不一,不信任 power_now 符号;约定与基础档一致:充电正/放电负)
 *     → 回退基础档 V×I
 *   - voltageMv/charging:直接取基础档(与 sysfs 同源,无需重复读)
 * - 注:部分机型(如 vivo)把 /sys/class/power_supply 目录对 shell 域锁死,
 *   两项直读均回退基础档,行为与不开启本档一致
 */
class PrivBatteryProvider(context: Context) : BatteryProvider {

    private val basic = BasicBatteryProvider(context)

    override suspend fun sample(): BatterySample? {
        val base = basic.sample() ?: return null
        // 门控改为"任一特权通道可用":Shizuku 常驻服务在跑时,无线调试关闭也可直读
        if (!PrivShell.canExec()) return base
        val out = PrivShell.exec(READ_CMD) ?: return base
        return merge(base, out)
    }

    /** shell 输出逐字段并入基础档:有效则覆盖,无效回退 */
    private fun merge(base: BatterySample, out: String): BatterySample {
        var temp = Float.NaN
        var power = Float.NaN
        for (line in out.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val value = line.substring(idx + 1).trim().toLongOrNull() ?: continue
            when (line.substring(0, idx).trim()) {
                LBL_TEMP -> temp = sanitizeTemp(value / 10f)
                LBL_POWER -> power = sanitizePower(value / 1_000_000f)
            }
        }
        return base.copy(
            batteryTempC = if (temp.isFinite()) temp else base.batteryTempC,
            powerW = if (power.isFinite()) applyChargingSign(power, base.charging) else base.powerW,
        )
    }

    // ===== 数值清洗(垃圾值一律按无效处理走回退) =====

    /** 电池温度(tenths °C→°C),接受 0..120°C */
    private fun sanitizeTemp(celsius: Float): Float =
        if (celsius in 0f..120f) celsius else Float.NaN

    /** power_now(μW→W):只取幅值(符号由充电状态决定),幅值 200W 内视为合理 */
    private fun sanitizePower(watts: Float): Float =
        if (watts.isFinite() && kotlin.math.abs(watts) < 200f) kotlin.math.abs(watts) else Float.NaN

    /** 符号约定与基础档一致:放电负,充电/未知正 */
    private fun applyChargingSign(magnitudeW: Float, charging: Boolean?): Float =
        if (charging == false) -magnitudeW else magnitudeW

    companion object {
        private const val READ_CMD =
            "for f in temp power_now; do echo \"\$f:\$(cat /sys/class/power_supply/battery/\$f 2>/dev/null)\"; done"
        private const val LBL_TEMP = "temp"
        private const val LBL_POWER = "power_now"
    }
}
