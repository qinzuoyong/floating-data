package com.example.batteryfloat.data

/**
 * 单次电池采样结果
 *
 * 语义约定(与重构前 BatteryMonitor 保持一致):
 * - [batteryTempC] < 0 表示本次温度无效,调用方跳过展示
 * - [powerW] 为 NaN 表示功耗不可用(如机型不报电流),不参与通知阈值比较
 */
data class BatterySample(
    val batteryTempC: Float,
    val powerW: Float,
    val voltageMv: Int,
    /** true=充电 false=放电 null=未知(决定功耗符号) */
    val charging: Boolean?,
)
