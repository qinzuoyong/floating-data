package com.example.batteryfloat.data

import android.content.Context
import android.util.Log
import com.example.batteryfloat.adb.AdbConnectionManager
import com.example.batteryfloat.adb.AdbState

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
 *   - socTempC/gpuTempC:thermal_zone 直读(批次 4 悬浮窗第三行消费),读不到为 null
 * - SoC/GPU 热区路径按 type 启发式发现一次并缓存;读取失败失效重发现
 */
class PrivBatteryProvider(context: Context) : BatteryProvider {

    private val basic = BasicBatteryProvider(context)

    /** SoC 热区 temp 路径缓存;null=未发现/未命中/已失效 */
    @Volatile
    private var socZonePath: String? = null

    /** GPU 热区 temp 路径缓存;null=未发现/未命中/已失效 */
    @Volatile
    private var gpuZonePath: String? = null

    /** 本轮发现已做过(含未命中);true 期间不再发现,避免无热区机型每个采样周期空跑 */
    @Volatile
    private var zonesResolved = false

    override suspend fun sample(): BatterySample? {
        val base = basic.sample() ?: return null
        if (AdbConnectionManager.state.value != AdbState.CONNECTED) return base
        if (!zonesResolved) discoverZones()
        val out = AdbConnectionManager.exec(buildReadCommand()) ?: return base
        return merge(base, out)
    }

    // ===== shell 命令与解析 =====

    /** 每次采样一条命令:带标签逐行输出,cat 失败仅输出空标签行,不影响其他字段 */
    private fun buildReadCommand(): String {
        val sb = StringBuilder(
            "for f in temp power_now; do echo \"\$f:\$(cat /sys/class/power_supply/battery/\$f 2>/dev/null)\"; done"
        )
        socZonePath?.let { sb.append("; echo \"soc:\$(cat $it 2>/dev/null)\"") }
        gpuZonePath?.let { sb.append("; echo \"gpu:\$(cat $it 2>/dev/null)\"") }
        return sb.toString()
    }

    /** 热区启发式发现:遍历 thermal_zone 的 type,命中即缓存 temp 路径;exec 失败保持待重试 */
    private suspend fun discoverZones() {
        val out = AdbConnectionManager.exec(DISCOVER_CMD)
        if (out == null) {
            zonesResolved = false
            return
        }
        zonesResolved = true
        var soc: String? = null
        var gpu: String? = null
        for (line in out.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val path = line.substring(0, idx).trim()
            val type = line.substring(idx + 1).trim()
            if (path.isEmpty() || type.isEmpty()) continue
            if (soc == null && SOC_ZONE_PATTERN.containsMatchIn(type)) soc = "$path/temp"
            if (gpu == null && GPU_ZONE_PATTERN.containsMatchIn(type)) gpu = "$path/temp"
        }
        socZonePath = soc
        gpuZonePath = gpu
        Log.i(TAG, "热区映射 soc=${soc ?: "未命中"} gpu=${gpu ?: "未命中"}" +
                "(未命中可 adb shell 'cat /sys/class/thermal/thermal_zone*/type' 核对命名)")
    }

    /** shell 输出逐字段并入基础档:有效则覆盖,无效回退;热区路径读失败时失效缓存 */
    private fun merge(base: BatterySample, out: String): BatterySample {
        var temp = Float.NaN
        var power = Float.NaN
        var soc = Float.NaN
        var gpu = Float.NaN
        for (line in out.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val value = line.substring(idx + 1).trim().toLongOrNull() ?: continue
            when (line.substring(0, idx).trim()) {
                LBL_TEMP -> temp = sanitizeTemp(value / 10f)
                LBL_POWER -> power = sanitizePower(value / 1_000_000f)
                LBL_SOC -> soc = normalizeZoneTemp(value)
                LBL_GPU -> gpu = normalizeZoneTemp(value)
            }
        }
        if (socZonePath != null && !soc.isFinite()) {
            socZonePath = null
            zonesResolved = false
        }
        if (gpuZonePath != null && !gpu.isFinite()) {
            gpuZonePath = null
            zonesResolved = false
        }
        return base.copy(
            batteryTempC = if (temp.isFinite()) temp else base.batteryTempC,
            powerW = if (power.isFinite()) applyChargingSign(power, base.charging) else base.powerW,
            socTempC = soc.takeIf { it.isFinite() },
            gpuTempC = gpu.takeIf { it.isFinite() },
        )
    }

    // ===== 数值清洗(垃圾值一律按无效处理走回退) =====

    /** 电池温度(tenths °C→°C),接受 0..120°C */
    private fun sanitizeTemp(celsius: Float): Float =
        if (celsius in 0f..120f) celsius else Float.NaN

    /** power_now(μW→W):只取幅值(符号由充电状态决定),幅值 200W 内视为合理 */
    private fun sanitizePower(watts: Float): Float =
        if (watts.isFinite() && kotlin.math.abs(watts) < 200f) kotlin.math.abs(watts) else Float.NaN

    /** 热区温度:标准毫度,兼容个别内核的十分度,接受 0..120°C */
    private fun normalizeZoneTemp(raw: Long): Float {
        val milli = raw / 1000f
        if (milli in 0f..120f) return milli
        val deci = raw / 10f
        return if (deci in 0f..120f) deci else Float.NaN
    }

    /** 符号约定与基础档一致:放电负,充电/未知正 */
    private fun applyChargingSign(magnitudeW: Float, charging: Boolean?): Float =
        if (charging == false) -magnitudeW else magnitudeW

    companion object {
        private const val TAG = "PrivBatteryProvider"
        private const val DISCOVER_CMD =
            "for z in /sys/class/thermal/thermal_zone*; do echo \"\$z:\$(cat \$z/type 2>/dev/null)\"; done"
        private const val LBL_TEMP = "temp"
        private const val LBL_POWER = "power_now"
        private const val LBL_SOC = "soc"
        private const val LBL_GPU = "gpu"

        /** SoC 热区 type 命名:Tensor 系 soc-thermal、骁龙系 cpu-0-0-step、通用 cpu-thermal/soc */
        private val SOC_ZONE_PATTERN =
            Regex("""(?i)(soc-thermal|cpu-0-0|cpu-thermal|apc0|^soc$)""")

        /** GPU 热区 type 命名:Tensor 系 gpu-thermal、骁龙系 gpuss-x */
        private val GPU_ZONE_PATTERN =
            Regex("""(?i)(gpu-thermal|gpuss|^gpu$)""")
    }
}
