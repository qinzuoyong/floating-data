package com.example.batteryfloat.ui.family

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.batteryfloat.R
import com.example.batteryfloat.family.AlertPlace
import com.example.batteryfloat.family.FamilyMember
import com.example.batteryfloat.family.PlaceStore
import com.example.batteryfloat.service.FamilyLocationService
import com.example.batteryfloat.ui.PrimaryActionButton
import com.example.batteryfloat.ui.SectionTitle
import com.example.batteryfloat.ui.theme.DesignSystem
import java.util.Locale

/** 检查频率可选项（分钟；下限由 [PlaceStore.INTERVAL_MIN_MINUTES] 约束） */
private val INTERVAL_CHOICES = listOf(5, 10, 15, 30)

/**
 * 地点提醒页（家人页子页）
 *
 * 总开关 + 检查频率 + 地点增删改查。判定与提醒由 [FamilyLocationService] 在收到家人位置时执行；
 * 本页只写本地存储，并在每次变更后同步定时轮询任务（关开关即撤销任务）。
 */
@Composable
internal fun FamilyAlertScreen(
    context: Context,
    members: List<FamilyMember>,
    onBack: () -> Unit
) {
    val placeStore = remember { PlaceStore.get(context) }
    val places by placeStore.places.collectAsState()
    var enabled by remember { mutableStateOf(placeStore.alertsEnabled()) }
    var interval by remember { mutableStateOf(placeStore.intervalMinutes()) }
    var editing by remember { mutableStateOf<AlertPlace?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AlertPlace?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(DesignSystem.PagePadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.SpacingM)
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.family_back)) }
        }
        item { SectionTitle(stringResource(R.string.family_alert_title)) }

        // 总开关（默认关：开启后会定时请求家人位置，需用户明确知情）
        item {
            Card(
                shape = RoundedCornerShape(DesignSystem.CornerL),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(DesignSystem.CardPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.family_alert_master_switch),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                enabled = on
                                placeStore.setAlertsEnabled(on)
                                // 开关是轮询任务的唯一门控：立即登记或撤销
                                FamilyLocationService.syncAlertPoll(context)
                            }
                        )
                    }
                    Spacer(Modifier.height(DesignSystem.SpacingXs))
                    Text(
                        text = stringResource(R.string.family_alert_master_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(DesignSystem.SpacingM))
                    Text(
                        text = stringResource(R.string.family_alert_interval),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(DesignSystem.SpacingXs))
                    // 用可换行的 FlowRow：4 个频率选项在窄屏（360dp 的真机）一行放不下，
                    // 原先固定单行会把最后一项挤出屏幕（真机实测）
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        for (choice in INTERVAL_CHOICES) {
                            TextButton(onClick = {
                                interval = choice
                                placeStore.setIntervalMinutes(choice)
                                FamilyLocationService.syncAlertPoll(context)
                            }) {
                                Text(
                                    text = stringResource(R.string.family_alert_interval_value, choice),
                                    fontWeight = if (choice == interval) FontWeight.Bold
                                    else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.family_alert_places_title)) }

        if (places.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.family_alert_places_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(places, key = { it.id }) { place ->
                AlertPlaceCard(
                    place = place,
                    onEdit = { editing = place },
                    onDelete = { deleting = place },
                    onToggle = { on ->
                        placeStore.update(place.copy(enabled = on))
                        FamilyLocationService.syncAlertPoll(context)
                    }
                )
            }
        }

        item {
            PrimaryActionButton(
                text = stringResource(R.string.family_alert_place_add),
                onClick = { creating = true },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text(
                text = stringResource(R.string.family_alert_privacy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (creating || editing != null) {
        PlaceEditorDialog(
            existing = editing,
            members = members,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { place ->
                if (place.id.isEmpty()) {
                    placeStore.add(
                        name = place.name,
                        lat = place.lat,
                        lng = place.lng,
                        radiusMeters = place.radiusMeters,
                        watchUids = place.watchUids
                    )
                } else {
                    placeStore.update(place)
                }
                creating = false
                editing = null
                FamilyLocationService.syncAlertPoll(context)
            }
        )
    }

    deleting?.let { place ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.family_alert_delete_title)) },
            text = { Text(stringResource(R.string.family_alert_delete_confirm, place.name)) },
            confirmButton = {
                TextButton(onClick = {
                    placeStore.remove(place.id)
                    deleting = null
                    FamilyLocationService.syncAlertPoll(context)
                }) { Text(stringResource(R.string.family_alert_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * 地点提醒入口卡片（家人列表内）
 *
 * @param enabled 总开关状态
 * @param placeCount 启用中的地点数
 * @param onClick 进入地点提醒页
 */
@Composable
internal fun FamilyAlertEntryCard(enabled: Boolean, placeCount: Int, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(DesignSystem.CornerL),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(DesignSystem.CardPadding)) {
            Text(
                text = stringResource(R.string.family_alert_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(DesignSystem.SpacingXs))
            Text(
                text = stringResource(R.string.family_alert_entry_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(DesignSystem.SpacingXs))
            Text(
                text = if (enabled) {
                    stringResource(R.string.family_alert_entry_on, placeCount)
                } else {
                    stringResource(R.string.family_alert_entry_off)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单个地点的卡片：名称、半径、监视范围与开关 */@Composable
private fun AlertPlaceCard(
    place: AlertPlace,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(DesignSystem.CornerL),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(DesignSystem.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = place.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(DesignSystem.SpacingXs))
            Text(
                text = stringResource(R.string.family_alert_place_radius, place.radiusMeters),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(DesignSystem.SpacingXs))
            Text(
                text = if (place.watchUids.isEmpty()) {
                    stringResource(R.string.family_alert_place_watch_all)
                } else {
                    stringResource(R.string.family_alert_place_watch_count, place.watchUids.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(DesignSystem.SpacingS))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(DesignSystem.CornerM)
                ) { Text(stringResource(R.string.family_alert_edit)) }
                Spacer(Modifier.width(DesignSystem.SpacingS))
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.family_alert_delete))
                }
            }
        }
    }
}

/**
 * 地点编辑对话框（新增与编辑共用）
 *
 * 选点走「手动输入经纬度」+「取某成员上次位置」两条 MVP 路径（地图点选后续再做，
 * 见 AGENTS.md 架构分层的地图抽象说明）。
 *
 * @param existing 待编辑地点；null=新增
 * @param members 家人列表（供「取某成员上次位置」与监视成员多选）
 * @param onSave 保存回调；id 为空串表示新增
 */
@Composable
private fun PlaceEditorDialog(
    existing: AlertPlace?,
    members: List<FamilyMember>,
    onDismiss: () -> Unit,
    onSave: (AlertPlace) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var latText by remember { mutableStateOf(existing?.let { formatCoord(it.lat) } ?: "") }
    var lngText by remember { mutableStateOf(existing?.let { formatCoord(it.lng) } ?: "") }
    var radiusText by remember {
        mutableStateOf((existing?.radiusMeters ?: AlertPlace.DEFAULT_RADIUS_M).toString())
    }
    var watchUids by remember { mutableStateOf(existing?.watchUids ?: emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val nameRequired = stringResource(R.string.family_alert_error_name)
    val coordInvalid = stringResource(R.string.family_alert_error_coord)
    val radiusInvalid = stringResource(
        R.string.family_alert_error_radius,
        PlaceStore.RADIUS_MIN_M,
        PlaceStore.RADIUS_MAX_M
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.family_alert_place_new_title
                    else R.string.family_alert_place_edit_title
                )
            )
        },
        text = {
            // 内容高度随成员数增长（每名成员一行「取家人上次位置」+ 一行监视勾选），
            // 而 M3 的 AlertDialog 不为 text 插槽提供滚动：矮屏或输入法弹出时下方内容
            // 会被裁掉且**无法到达**（模拟器实测 853×480dp、4 名成员时「取家人上次位置」
            // 与「监视成员」整块消失，在对话框内拖动无任何反应）。故显式开启纵向滚动。
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.family_alert_name_label)) },
                    singleLine = true
                )
                Spacer(Modifier.height(DesignSystem.SpacingS))
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text(stringResource(R.string.family_alert_lat_label)) },
                    singleLine = true
                )
                Spacer(Modifier.height(DesignSystem.SpacingS))
                OutlinedTextField(
                    value = lngText,
                    onValueChange = { lngText = it },
                    label = { Text(stringResource(R.string.family_alert_lng_label)) },
                    singleLine = true
                )
                Spacer(Modifier.height(DesignSystem.SpacingS))
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it },
                    label = { Text(stringResource(R.string.family_alert_radius_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.family_alert_radius_hint,
                                PlaceStore.RADIUS_MIN_M,
                                PlaceStore.RADIUS_MAX_M
                            )
                        )
                    },
                    singleLine = true
                )

                // 取某成员上次位置一键填入（地图点选之前的 MVP 选点方式）
                val withLocation = members.filter { it.lastLat != null && it.lastLng != null }
                if (withLocation.isEmpty()) {
                    Spacer(Modifier.height(DesignSystem.SpacingS))
                    Text(
                        text = stringResource(R.string.family_alert_use_member_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    for (member in withLocation) {
                        TextButton(onClick = {
                            latText = formatCoord(member.lastLat ?: return@TextButton)
                            lngText = formatCoord(member.lastLng ?: return@TextButton)
                        }) {
                            Text(
                                stringResource(R.string.family_alert_use_member_location) +
                                    "：" + member.displayName
                            )
                        }
                    }
                }

                Spacer(Modifier.height(DesignSystem.SpacingS))
                Text(
                    text = stringResource(R.string.family_alert_watch_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                for (member in members) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = watchUids.contains(member.uid),
                            onCheckedChange = { checked ->
                                watchUids = if (checked) watchUids + member.uid
                                else watchUids - member.uid
                            }
                        )
                        Text(member.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                error?.let {
                    Spacer(Modifier.height(DesignSystem.SpacingS))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lat = latText.trim().toDoubleOrNull()
                val lng = lngText.trim().toDoubleOrNull()
                val radius = radiusText.trim().toIntOrNull()
                error = when {
                    name.isBlank() -> nameRequired
                    lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0 ->
                        coordInvalid
                    radius == null || radius !in PlaceStore.RADIUS_MIN_M..PlaceStore.RADIUS_MAX_M ->
                        radiusInvalid
                    else -> null
                }
                if (error == null) {
                    onSave(
                        AlertPlace(
                            id = existing?.id ?: "",
                            name = name.trim(),
                            lat = lat!!,
                            lng = lng!!,
                            radiusMeters = radius!!,
                            watchUids = watchUids,
                            enabled = existing?.enabled ?: true
                        )
                    )
                }
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** 坐标文本：固定 6 位小数（与定位精度相称，且便于手工核对与脚本注入） */
private fun formatCoord(value: Double): String = String.format(Locale.US, "%.6f", value)
