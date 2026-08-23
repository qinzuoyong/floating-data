package com.example.batteryfloat.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.model.LatLng
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 百度地图实现（TextureMapView + Compose AndroidView 包装）
 *
 * 生命周期：factory 创建时持有 TextureMapView；组合销毁时调用
 * onPause/onDestroy（百度 SDK 要求成对调用，否则泄漏）。
 */
class BaiduMapProvider : MapProvider {

    @Composable
    override fun FamilyMap(
        center: MapPoint?,
        markers: List<MapPoint>,
        modifier: Modifier
    ) {
        val holder = remember { MapViewHolder() }

        DisposableEffect(Unit) {
            onDispose {
                holder.mapView?.onPause()
                holder.mapView?.onDestroy()
                holder.mapView = null
            }
        }

        AndroidView(
            factory = { ctx: Context ->
                TextureMapView(ctx).also { view ->
                    holder.mapView = view
                    holder.baiduMap = view.map
                    view.map.isBuildingsEnabled = false
                    applyMapData(view.map, center, markers, holder)
                }
            },
            update = { view ->
                holder.mapView = view
                holder.baiduMap = view.map
                applyMapData(view.map, center, markers, holder)
            },
            modifier = modifier
        )
    }

    /** 中心定位 + 全量标记刷新（复用已创建 Marker） */
    private fun applyMapData(
        map: BaiduMap,
        center: MapPoint?,
        markers: List<MapPoint>,
        holder: MapViewHolder
    ) {
        // 移动镜头：有标记则缩放至全可见，否则以 center 为中心
        if (markers.isNotEmpty()) {
            val points = markers.map { LatLng(it.lat, it.lng) }
            val bounds = com.baidu.mapapi.model.LatLngBounds.Builder().apply {
                points.forEach { include(it) }
            }.build()
            map.animateMapStatus(
                com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLngBounds(bounds)
            )
        } else if (center != null) {
            map.animateMapStatus(
                MapStatusUpdateFactory.newLatLngZoom(LatLng(center.lat, center.lng), 15f)
            )
        }

        // 标记差量更新：复用 uid 匹配的 Marker（MapPoint.title 约定为标记 id）
        val wanted = markers.associateBy { it.title }
        val existing = holder.markers
        // 移除已消失
        val toRemove = existing.filter { it.title !in wanted.keys }
        toRemove.forEach { it.remove() }
        existing.removeAll(toRemove.toSet())
        // 新增
        for ((title, point) in wanted) {
            if (existing.none { it.title == title }) {
                val marker = map.addOverlay(
                    MarkerOptions()
                        .position(LatLng(point.lat, point.lng))
                        .title(point.title)
                        .icon(BitmapDescriptorFactory.fromBitmap(defaultMarkerBitmap()))
                ) as? Marker
                marker?.let { m ->
                    existing.add(m)
                }
            }
        }
    }

    /**
     * 生成默认 marker 位图（蓝色圆点+白边），避免依赖可绘制资源加载
     */
    private fun defaultMarkerBitmap(): Bitmap {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2).toFloat(), ring)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt() }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2 - 6).toFloat(), dot)
        return bmp
    }

    /** 持有地图视图与已添加 Marker 的组合状态 */
    private class MapViewHolder {
        var mapView: TextureMapView? = null
        var baiduMap: BaiduMap? = null
        val markers = CopyOnWriteArrayList<Marker>()
    }
}