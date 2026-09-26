package com.example.batteryfloat.ui.family

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.R
import com.example.batteryfloat.family.FamilyMember
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.p2p.SignalClient
import com.example.batteryfloat.ui.SectionTitle
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 家人列表主内容
 *
 * 只负责列表编排与自家状态（家庭码、退出确认），各区块下沉：
 * 加入审核提示/未加入引导/待审申请（FamilyJoinSection）、
 * 成员卡片（FamilyMemberSection）、家庭码与开关卡片（FamilyFamilyCodeCard）。
 */
@Composable
internal fun FamilyListContent(
    context: Context,
    prefs: SharedPreferences,
    store: FamilyStore,
    members: Map<String, FamilyMember>,
    connection: SignalClient.State,
    serviceOn: Boolean,
    onToggleService: (Boolean) -> Unit,
    onAddFamily: () -> Unit,
    onOpenMap: (FamilyMember) -> Unit,
    onLeaveFamily: () -> Unit
) {
    val familyCode = prefs.getString(PrefsKeys.FAMILY_CODE, "") ?: ""
    var confirmLeave by remember { mutableStateOf(false) }
    // 加入审核：创建人视角的待审申请 + 加入者视角的审核状态
    val pendingJoins by store.pendingJoins.collectAsState()
    val joinState by store.joinState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(DesignSystem.PagePadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.SpacingM)
    ) {
        item { SectionTitle(stringResource(R.string.family_title)) }

        // 加入审核状态（加入者视角：置顶醒目）
        familyJoinStateHints(familyCode, joinState)

        if (familyCode.isBlank()) {
            // 未加入家庭：引导创建/加入
            familyNotJoinedCard(onAddFamily)
        } else {
            // 已加入家庭：家人列表置顶，便于快速查看成员
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
                        onOpenMap = { onOpenMap(member) },
                        onSetNote = { store.setMemberNote(member.uid, it) }
                    )
                }
            }

            // 待审核加入申请（创建人视角：批准/拒绝）
            familyPendingJoinItems(context, store, pendingJoins)

            // 家庭码卡片（服务开关/我的信息/加入/退出家庭，沉底）
            item {
                FamilyFamilyCodeCard(
                    prefs = prefs,
                    store = store,
                    familyCode = familyCode,
                    connection = connection,
                    serviceOn = serviceOn,
                    onToggleService = onToggleService,
                    onAddFamily = onAddFamily,
                    onLeaveRequest = { confirmLeave = true }
                )
            }
        }
    }

    // 退出家庭确认对话框
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.family_leave_title)) },
            text = { Text(stringResource(R.string.family_leave_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onLeaveFamily()
                    confirmLeave = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
