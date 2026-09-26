package com.example.batteryfloat.ui.family

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.R
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.service.FamilyLocationService
import com.example.batteryfloat.ui.PrimaryActionButton
import com.example.batteryfloat.ui.SectionTitle
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 家人页「加入」相关区块（LazyListScope 扩展，供 FamilyListContent 组装）
 *
 * 拆分的只是文件边界：item/items 的结构、顺序与内容与拆分前一致。
 */

/** 加入审核状态提示（加入者视角：置顶醒目） */
internal fun LazyListScope.familyJoinStateHints(
    familyCode: String,
    joinState: FamilyStore.JoinState
) {
    if (familyCode.isNotBlank() && joinState == FamilyStore.JoinState.PENDING) {
        item {
            Text(
                text = stringResource(R.string.family_join_pending_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else if (familyCode.isNotBlank() && joinState == FamilyStore.JoinState.REJECTED) {
        item {
            Text(
                text = stringResource(R.string.family_join_rejected_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 未加入家庭：引导创建/加入 */
internal fun LazyListScope.familyNotJoinedCard(onAddFamily: () -> Unit) {
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
}

/** 待审核加入申请（创建人视角：批准/拒绝） */
internal fun LazyListScope.familyPendingJoinItems(
    context: Context,
    store: FamilyStore,
    pendingJoins: Map<String, String>
) {
    if (pendingJoins.isEmpty()) return
    item { SectionTitle(stringResource(R.string.family_join_requests)) }
    items(pendingJoins.entries.toList(), key = { "req_" + it.key }) { (uid, name) ->
        Card(
            shape = RoundedCornerShape(DesignSystem.CornerL),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(DesignSystem.CardPadding)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    // 先移除申请，避免批准后与成员列表 key 冲突
                    store.removePendingJoin(uid)
                    FamilyLocationService.approveJoin(context, uid)
                }) {
                    Text(stringResource(R.string.family_join_approve))
                }
                TextButton(onClick = {
                    store.removePendingJoin(uid)
                    FamilyLocationService.rejectJoin(context, uid)
                }) {
                    Text(stringResource(R.string.family_join_reject))
                }
            }
        }
    }
}
