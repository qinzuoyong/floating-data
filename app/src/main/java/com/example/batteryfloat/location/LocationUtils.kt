package com.example.batteryfloat.location

/**
 * 定位工具：两点球面距离（Haversine）与展示格式化
 */
object LocationUtils {

    /**
     * 计算两经纬度点间的球面距离
     *
     * @param lat1 点 1 纬度
     * @param lng1 点 1 经度
     * @param lat2 点 2 纬度
     * @param lng2 点 2 经度
     * @return 距离（米）
     */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusM = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * earthRadiusM * Math.asin(Math.sqrt(a))
    }

    /**
     * 距离数值文案（不含「距离：」前缀，前缀由 UI 字符串资源拼接）：
     * 不足 1 公里显示米，否则公里（1 位小数）
     *
     * @param meters 距离（米）
     * @return 如「850 米」或「3.2 公里」
     */
    fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            meters.toInt().toString() + " 米"
        } else {
            String.format("%.1f", meters / 1000.0) + " 公里"
        }
    }
}