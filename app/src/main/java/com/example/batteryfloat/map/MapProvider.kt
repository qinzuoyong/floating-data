package com.example.batteryfloat.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 地图标记点 */
data class MapPoint(
    val lat: Double,
    val lng: Double,
    val title: String = ""
)

/**
 * 地图提供器抽象（先百度地图，高德后补）
 * 各 SDK 以 Compose 组件形式接入，FamilyMapScreen 不关心具体实现。
 */
interface MapProvider {

    /** 渲染地图：无 marker 时以 center 为中心；有 marker 时自动缩放到全部标记 */
    @Composable
    fun FamilyMap(
        center: MapPoint?,
        markers: List<MapPoint>,
        modifier: Modifier = Modifier
    )
}
