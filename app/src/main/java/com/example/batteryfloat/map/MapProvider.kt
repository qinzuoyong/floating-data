package com.example.batteryfloat.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 地图标记点 */
data class MapPoint(
    val lat: Double,
    val lng: Double,
    val title: String = "",
    /** 展示名（信息窗口用）：蓝点=我的位置，红点=家人显示名；空串时 JS 侧回退 title */
    val label: String = ""
)

/**
 * 地图提供器抽象（WebView 加载百度 JS API，零原生体积）
 * 各实现以 Compose 组件形式接入，FamilyMapScreen 不关心具体实现。
 */
interface MapProvider {

    /**
     * 渲染地图
     *
     * @param myLocation 我的位置（蓝点，可空——定位失败时只显示家人）
     * @param targets 家人位置列表（红点，>=2 个时自动缩放至全部可见）
     * @param modifier 修饰符
     * @param onAddress 逆地理编码结果回调（uid → 详细地址；JS 地图异步编码后回传）
     */
    @Composable
    fun FamilyMap(
        myLocation: MapPoint?,
        targets: List<MapPoint>,
        modifier: Modifier = Modifier,
        onAddress: (String, String) -> Unit = { _, _ -> }
    )
}