package com.example.batteryfloat.location

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 国内坐标系转换工具（标准开源算法的纯数学实现，无第三方依赖）
 *
 * 坐标系链路：GPS/WGS-84 →(国测局偏移)→ GCJ-02 →(百度偏移)→ BD-09
 * 本项目约定：定位层统一产出 GCJ-02（GPS 源在采集处转换；
 * vivo NLP 与高德 SDK 本就返回 GCJ-02），百度地图渲染入口再转 BD-09。
 */
object CoordTransform {

    private const val RAD = PI / 180.0
    private const val A = 6378245.0                   // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594323     // 椭球偏心率平方

    /** 是否在中国境外（境外无 GCJ 偏移，原样返回） */
    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y +
            0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * RAD) + 20.0 * sin(2.0 * x * RAD)) * 2.0 / 3.0
        ret += (20.0 * sin(y * RAD) + 40.0 * sin(y / 3.0 * RAD)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * RAD) + 320.0 * sin(y * RAD / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x +
            0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * RAD) + 20.0 * sin(2.0 * x * RAD)) * 2.0 / 3.0
        ret += (20.0 * sin(x * RAD) + 40.0 * sin(x / 3.0 * RAD)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * RAD) + 300.0 * sin(x / 30.0 * RAD)) * 2.0 / 3.0
        return ret
    }

    /**
     * WGS-84 → GCJ-02（火星坐标）
     *
     * @return Pair(lat, lng)；境外坐标原样返回
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat * RAD
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) * sqrtMagic)
        dLng = (dLng * 180.0) / (A * sqrtMagic * cos(radLat))
        return (lat + dLat) to (lng + dLng)
    }

    /**
     * GCJ-02 → BD-09（百度坐标）
     *
     * @return Pair(lat, lng)
     */
    fun gcj02ToBd09(lat: Double, lng: Double): Pair<Double, Double> {
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * RAD * 3000.0 / 180.0)
        val theta = atan2(lat, lng) + 0.000003 * cos(lng * RAD * 3000.0 / 180.0)
        val bdLat = z * sin(theta) + 0.006
        val bdLng = z * cos(theta) + 0.0065
        return bdLat to bdLng
    }
}
