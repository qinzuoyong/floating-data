package com.example.batteryfloat.ui.family

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.family.FamilyMember
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.map.BaiduMapProvider
import com.example.batteryfloat.map.MapPoint
import com.example.batteryfloat.ui.PrimaryActionButton
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 家人位置地图页（百度地图）
 *
 * 顶部地图（标记家人位置）+ 底部信息卡（名称/上次时间/精度/重新获取）。
 */
@Composable
fun FamilyMapScreen(
    member: FamilyMember,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { FamilyStore.get(context) }
    val members by store.members.collectAsState()
    // 实时成员（地图打开期间收到新位置自动刷新标记）
    val live = members[member.uid] ?: member

    Box(modifier = Modifier.fillMaxSize()) {
        if (live.lastLat != null && live.lastLng != null) {
            val mapProvider = remember { BaiduMapProvider() }
            mapProvider.FamilyMap(
                center = MapPoint(live.lastLat!!, live.lastLng!!, title = "center"),
                markers = listOf(
                    MapPoint(live.lastLat!!, live.lastLng!!, title = live.uid)
                ),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 无位置：占位
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFE8EEF5)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无位置，点击下方「重新获取」",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 返回按钮
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(DesignSystem.SpacingM)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(DesignSystem.CornerM)
                )
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }

        // 底部信息卡
        Card(
            shape = RoundedCornerShape(DesignSystem.CornerL),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(DesignSystem.PagePadding)
        ) {
            Column(modifier = Modifier.padding(DesignSystem.CardPaddingLarge)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = live.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(DesignSystem.SpacingXs))
                        Text(
                            text = locationDetail(live),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(DesignSystem.SpacingM))
                PrimaryActionButton(
                    text = "重新获取位置",
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
    }
}

/** 位置详情：经纬度 + 相对时间 + 精度 */
private fun locationDetail(member: FamilyMember): String {
    val lat = member.lastLat ?: return "暂无位置"
    val lng = member.lastLng ?: return "暂无位置"
    val time = member.lastTs?.let { formatRelativeTime(it) } ?: ""
    val acc = member.lastAccuracy?.let { "，精度 ±" + it.toInt() + "m" } ?: ""
    return String.format("%.5f, %.5f  ·  %s%s", lat, lng, time, acc)
}