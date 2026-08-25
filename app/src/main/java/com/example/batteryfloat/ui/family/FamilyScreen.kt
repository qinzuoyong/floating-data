package com.example.batteryfloat.ui.family

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.R
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.family.FamilyMember
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.p2p.SignalClient
import com.example.batteryfloat.service.FamilyLocationService
import com.example.batteryfloat.ui.PrimaryActionButton
import com.example.batteryfloat.ui.SectionTitle
import com.example.batteryfloat.ui.theme.DesignSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 家人 Tab 内部路由 */
private sealed interface FamilyRoute {
    data object List : FamilyRoute
    data object Add : FamilyRoute
    data class Map(val member: FamilyMember) : FamilyRoute
}

/**
 * 家人位置共享主页
 *
 * 自包含：定位/通知权限请求、前台服务启停、家庭码加入（AddFamilyScreen）、
 * 成员列表（上次位置/在线状态/获取位置/查看地图/删除）、隐私开关。
 */
@Composable
fun FamilyScreen(
    prefs: SharedPreferences,
    onBeforeExternalIntent: () -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { FamilyStore.get(context) }
    val members by store.members.collectAsState()
    val connection by FamilyLocationService.connection.collectAsState()

    var route by remember { mutableStateOf<FamilyRoute>(FamilyRoute.List) }
    var serviceOn by remember { mutableStateOf(isServiceRunning(context)) }

    // 定位 + 通知权限（首次进入请求；后台定位用于常驻响应）
    // 权限弹窗是独立系统 Activity，会触发 MainActivity.onUserLeaveHint；
    // 必须先经 onBeforeExternalIntent 标记"外部跳转"，避免 HIDE_RECENTS 把应用 finish 掉
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 授权后：未加入家庭则引导加入，已加入则自动开启服务
        if (result.values.any { it }) {
            val code = prefs.getString(PrefsKeys.FAMILY_CODE, "") ?: ""
            if (code.isBlank()) {
                route = FamilyRoute.Add
            } else {
                FamilyLocationService.start(context)
                serviceOn = true
            }
        }
    }
    LaunchedEffect(Unit) {
        // 仅请求前台定位 + 通知权限（后台位置权限无需：前台服务响应定位用 while-in-use 即可）
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter { p ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            onBeforeExternalIntent()
            permissionLauncher.launch(missing.toTypedArray())
        } else if ((prefs.getString(PrefsKeys.FAMILY_CODE, "") ?: "").isNotBlank() && !isServiceRunning(context)) {
            // 已授权且已加入家庭：自动开启位置共享服务，保证可被家人请求到位置
            FamilyLocationService.start(context)
            serviceOn = true
        }
    }

    when (val r = route) {
        is FamilyRoute.Map -> FamilyMapScreen(
            member = r.member,
            onBack = { route = FamilyRoute.List },
            onRefresh = { FamilyLocationService.requestLocation(context, r.member.uid) }
        )
        FamilyRoute.Add -> AddFamilyScreen(
            onDone = {
                route = FamilyRoute.List
                // 加入家庭后自动开启位置共享服务
                FamilyLocationService.start(context)
                serviceOn = true
            },
            // 权限弹窗会触发 MainActivity.onUserLeaveHint，需标记外部跳转防 finish
            onBeforeExternalIntent = onBeforeExternalIntent
        )
        FamilyRoute.List -> FamilyListContent(
            context = context,
            prefs = prefs,
            store = store,
            members = members,
            connection = connection,
            serviceOn = serviceOn,
            onToggleService = { on ->
                if (on) {
                    FamilyLocationService.start(context)
                    serviceOn = true
                } else {
                    FamilyLocationService.stop(context)
                    serviceOn = false
                }
            },
            onAddFamily = { route = FamilyRoute.Add },
            onOpenMap = { route = FamilyRoute.Map(it) }
        )
    }
}

/** 家人列表主内容 */
@Composable
private fun FamilyListContent(
    context: Context,
    prefs: SharedPreferences,
    store: FamilyStore,
    members: Map<String, FamilyMember>,
    connection: SignalClient.State,
    serviceOn: Boolean,
    onToggleService: (Boolean) -> Unit,
    onAddFamily: () -> Unit,
    onOpenMap: (FamilyMember) -> Unit
) {
    val familyCode = prefs.getString(PrefsKeys.FAMILY_CODE, "") ?: ""
    var myName by remember { mutableStateOf(prefs.getString(PrefsKeys.FAMILY_MY_NAME, "") ?: "") }
    var allowLoc by remember { mutableStateOf(store.allowLocReq()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(DesignSystem.PagePadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.SpacingM)
    ) {
        item { SectionTitle(stringResource(R.string.family_title)) }

        if (familyCode.isBlank()) {
            // 未加入家庭：引导创建/加入
            item {
                Card(
                    shape = RoundedCornerShape(DesignSystem.CornerL),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone)
                ) {
                    Column(
                        modifier = Modifier.padding(DesignSystem.CardPaddingLarge),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.People,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(DesignSystem.SpacingM))
                        Text(
                            text = stringResource(R.string.family_join_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(DesignSystem.SpacingL))
                        PrimaryActionButton(
                            text = stringResource(R.string.family_join_button),
                            onClick = onAddFamily,
                            icon = {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        } else {
            // 已加入家庭：服务开关 + 状态 + 我的信息
            item {
                Card(
                    shape = RoundedCornerShape(DesignSystem.CornerL),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(DesignSystem.CardPaddingLarge)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.family_code_label, familyCode),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(DesignSystem.SpacingXs))
                                Text(
                                    text = connectionText(connection, serviceOn),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = connectionColor(connection, serviceOn)
                                )
                            }
                            Switch(
                                checked = serviceOn,
                                onCheckedChange = onToggleService
                            )
                        }
                        Spacer(Modifier.height(DesignSystem.SpacingM))
                        Text(
                            text = stringResource(
                                R.string.family_my_name_label,
                                myName.ifBlank { stringResource(R.string.family_unset) }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(DesignSystem.SpacingM))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.family_allow_loc_req),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Switch(
                                checked = allowLoc,
                                onCheckedChange = { v ->
                                    allowLoc = v
                                    store.setAllowLocReq(v)
                                }
                            )
                        }
                        Spacer(Modifier.height(DesignSystem.SpacingM))
                        Text(
                            text = stringResource(R.string.family_change_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(DesignSystem.SpacingS))
                        PrimaryActionButton(
                            text = stringResource(R.string.family_join_new),
                            onClick = onAddFamily,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 成员列表
        if (familyCode.isNotBlank()) {
            item { SectionTitle(stringResource(R.string.family_members_title)) }
            if (members.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.family_empty_hint, familyCode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(members.values.toList(), key = { it.uid }) { member ->
                    MemberCard(
                        member = member,
                        onRequestLocation = { FamilyLocationService.requestLocation(context, member.uid) },
                        onOpenMap = { onOpenMap(member) },
                        onRemove = { store.removeMember(member.uid) }
                    )
                }
            }
        }
    }
}

/** 单个成员卡片 */
@Composable
private fun MemberCard(
    member: FamilyMember,
    onRequestLocation: () -> Unit,
    onOpenMap: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(DesignSystem.CornerL),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(DesignSystem.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 在线状态圆点
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (member.online) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(DesignSystem.SpacingS))
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.family_delete_member),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(DesignSystem.SpacingS))
            Text(
                text = lastLocationText(member),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(DesignSystem.SpacingM))
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.SpacingS)) {
                PrimaryActionButton(
                    text = stringResource(R.string.family_get_location),
                    onClick = onRequestLocation,
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenMap,
                    shape = RoundedCornerShape(DesignSystem.CornerM),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(DesignSystem.SpacingS))
                    Text(stringResource(R.string.family_map))
                }
            }
        }
    }
}

/**
 * 上次位置文案：无记录或「相对时间 + 精度」
 *
 * @param member 成员
 * @return 展示文案
 */
@Composable
private fun lastLocationText(member: FamilyMember): String {
    val ts = member.lastTs ?: return stringResource(R.string.family_last_loc_none)
    val acc = member.lastAccuracy?.let {
        stringResource(R.string.family_accuracy_suffix, it.toInt())
    } ?: ""
    return stringResource(R.string.family_last_loc_prefix, formatRelativeTime(ts) + acc)
}

/** 相对时间：<1 分钟=刚刚，<60 分钟=x 分钟前，<24h=x 小时前，否则 MM-dd HH:mm */
internal fun formatRelativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> (diff / 60_000L).toString() + " 分钟前"
        diff < 86_400_000L -> (diff / 3_600_000L).toString() + " 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

/** 连接状态文案（字符串资源） */
@Composable
private fun connectionText(state: SignalClient.State, serviceOn: Boolean): String {
    if (!serviceOn) return stringResource(R.string.family_service_off)
    return when (state) {
        is SignalClient.State.Connected -> stringResource(R.string.family_connected)
        is SignalClient.State.Connecting -> stringResource(R.string.family_connecting)
        else -> stringResource(R.string.family_not_connected)
    }
}

/** 连接状态颜色：未开启灰、已连接绿、其余错误色 */
@Composable
private fun connectionColor(state: SignalClient.State, serviceOn: Boolean): Color {
    if (!serviceOn) return MaterialTheme.colorScheme.onSurfaceVariant
    return when (state) {
        is SignalClient.State.Connected -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.error
    }
}

/** 家人服务是否在运行（进程内查询） */
private fun isServiceRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    return am.getRunningServices(100).any {
        it.service.className == FamilyLocationService::class.java.name
    }
}