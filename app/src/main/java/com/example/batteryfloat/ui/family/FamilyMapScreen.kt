package com.example.batteryfloat.ui.family

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.R
import com.example.batteryfloat.family.FamilyMember
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.location.LocationUtils
import com.example.batteryfloat.location.OnDemandLocationProvider
import com.example.batteryfloat.map.MapPoint
import com.example.batteryfloat.map.WebMapProvider
import com.example.batteryfloat.ui.PrimaryActionButton
import com.example.batteryfloat.ui.theme.DesignSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 家人位置地图页（WebView 加载百度 JS API）
 *
 * 顶部地图：我的位置（蓝点）+ 家人位置（红点）+ 两点连线；
 * 底部信息卡：名称 / 坐标与时间 / 距离 / 重新获取。
 * 打开时取一次我的定位（复用 [OnDemandLocationProvider]），重新获取时同步刷新。
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

    var myLoc by remember { mutableStateOf<MapPoint?>(null) }
    var locating by remember { mutableStateOf(false) }
    // JS 逆地理编码结果：uid → 详细地址（如 上海市浦东新区XX路XX号）
    var address by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 打开地图页时取一次我的位置
    LaunchedEffect(Unit) {
        refreshMyLocation(context, scope, locating, { locating = it }, { myLoc = it })
    }

    // 距离：我的位置与家人位置（两者齐备时显示）
    val distanceText = remember(myLoc, live.lastLat, live.lastLng) {
        val my = myLoc
        val lat = live.lastLat
        val lng = live.lastLng
        if (my != null && lat != null && lng != null) {
            LocationUtils.formatDistance(
                LocationUtils.distanceMeters(my.lat, my.lng, lat, lng)
            )
        } else null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (live.lastLat != null && live.lastLng != null) {
            val mapProvider = remember { WebMapProvider() }
            mapProvider.FamilyMap(
                myLocation = myLoc,
                targets = listOf(
                    MapPoint(live.lastLat!!, live.lastLng!!, title = live.uid)
                ),
                modifier = Modifier.fillMaxSize(),
                onAddress = { uid, addr ->
                    if (uid == live.uid) address = addr
                }
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
                    text = stringResource(R.string.family_map_no_location),
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.family_back))
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
                        // 详细地址优先（JS 逆地理编码），未就绪时回退坐标
                        Text(
                            text = address ?: locationDetail(live),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (address != null) {
                            Spacer(Modifier.height(DesignSystem.SpacingXs))
                            Text(
                                text = locationDetail(live),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (distanceText != null) {
                            Spacer(Modifier.height(DesignSystem.SpacingXs))
                            Text(
                                text = stringResource(R.string.family_distance_prefix, distanceText),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(DesignSystem.SpacingM))
                PrimaryActionButton(
                    text = stringResource(R.string.family_map_refresh),
                    onClick = {
                        onRefresh()
                        refreshMyLocation(context, scope, locating, { locating = it }, { myLoc = it })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
    }
}

/**
 * 取一次我的位置（需定位权限；失败时置 null，不影响家人位置显示）
 *
 * @param context 上下文
 * @param scope 协程作用域
 * @param locating 是否正在定位（防重入）
 * @param setLocating 更新定位中状态
 * @param setMyLoc 更新我的位置
 */
private fun refreshMyLocation(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    locating: Boolean,
    setLocating: (Boolean) -> Unit,
    setMyLoc: (MapPoint?) -> Unit
) {
    if (locating) return
    val hasLoc = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    if (!hasLoc) return

    setLocating(true)
    scope.launch {
        val loc = withContext(Dispatchers.Default) {
            OnDemandLocationProvider(context).getCurrentLocation()
        }
        setMyLoc(loc?.let { MapPoint(it.lat, it.lng, title = "me") })
        setLocating(false)
    }
}

/** 位置详情：经纬度 + 相对时间 + 精度（字符串资源拼接） */
private fun locationDetail(member: FamilyMember): String {
    val lat = member.lastLat ?: return ""
    val lng = member.lastLng ?: return ""
    val time = member.lastTs?.let { formatRelativeTime(it) } ?: ""
    val acc = member.lastAccuracy?.let { "（精度 ±" + it.toInt() + "m）" } ?: ""
    return String.format("%.5f, %.5f  ·  %s%s", lat, lng, time, acc)
}