package com.example.batteryfloat.data

/**
 * 电池数据源抽象:基础档(公开 API)与增强档(特权通道)实现同一接口,
 * 悬浮窗/通知的消费逻辑与具体数据来源解耦
 */
interface BatteryProvider {
    /** 采样一次;内部异常自行捕获并返回 null,消费方跳过该轮 */
    suspend fun sample(): BatterySample?
}
