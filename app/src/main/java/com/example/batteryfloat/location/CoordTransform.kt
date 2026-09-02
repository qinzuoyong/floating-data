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
    /** GCJ→BD 扰动角单位（国测局标准算法定义） */
    private const val X_PI = PI * 3000.0 / 180.0

    /** 是否在中国境外（境外无 GCJ 偏移，原样返回） */
    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    /**
     * 港澳台地区：无 GCJ 偏移（香港/澳门坐标系统未偏移，台湾用 TWD97 系），
     * 落在矩形内时同样原样返回，避免强行偏移产生数百米误差。
     */
    private fun inNonGcjRegion(lat: Double, lng: Double): Boolean =
        (lng in 113.82..114.44 && lat in 22.15..22.57) ||   // 香港
            (lng in 113.52..113.63 && lat in 22.10..22.24) ||   // 澳门
            (lng in 119.90..122.01 && lat in 21.87..25.35)      // 台湾（含近岛）

    /**
     * 国测局偏移的纬度变换项。
     * 注意：三角项入参是"度数值直接乘 PI"（国测局混淆算法的原始定义，
     * 全球所有标准实现如此），不是把度转成弧度——勿"修正"为 RAD，
     * 否则偏移量算错数百米（GPS 源在百度地图上整体偏移）。
     */
    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y +
            0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    /** 经度变换项（单位约定同 [transformLat]，勿改回 RAD） */
    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x +
            0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /**
     * WGS-84 → GCJ-02（火星坐标）
     *
     * @return Pair(lat, lng)；境外及港澳台坐标原样返回
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng) || inNonGcjRegion(lat, lng)) return lat to lng
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
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lng) + 0.000003 * cos(lng * X_PI)
        val bdLat = z * sin(theta) + 0.006
        val bdLng = z * cos(theta) + 0.0065
        return bdLat to bdLng
    }
}
