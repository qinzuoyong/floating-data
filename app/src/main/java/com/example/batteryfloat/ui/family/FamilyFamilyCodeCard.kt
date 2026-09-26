package com.example.batteryfloat.ui.family

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.R
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.p2p.SignalClient
import com.example.batteryfloat.ui.PrimaryActionButton
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 家庭码与开关卡片（列表沉底）
 *
 * 含：服务开关与连接状态、我的备注名、隐私开关（允许家人请求我的位置）、
 * 更换/加入家庭、退出家庭。自家状态（我的名称、隐私开关）在本组件内持有。
 */
@Composable
internal fun FamilyFamilyCodeCard(
    prefs: SharedPreferences,
    store: FamilyStore,
    familyCode: String,
    connection: SignalClient.State,
    serviceOn: Boolean,
    onToggleService: (Boolean) -> Unit,
    onAddFamily: () -> Unit,
    onLeaveRequest: () -> Unit
) {
    var myName by remember { mutableStateOf(prefs.getString(PrefsKeys.FAMILY_MY_NAME, "") ?: "") }
    var allowLoc by remember { mutableStateOf(store.allowLocReq()) }

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
            Spacer(Modifier.height(DesignSystem.SpacingS))
            OutlinedButton(
                onClick = onLeaveRequest,
                shape = RoundedCornerShape(DesignSystem.CornerM),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.family_leave))
            }
        }
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
