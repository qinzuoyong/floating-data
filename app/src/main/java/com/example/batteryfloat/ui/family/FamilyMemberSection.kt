package com.example.batteryfloat.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.R
import com.example.batteryfloat.family.FamilyMember
import com.example.batteryfloat.ui.theme.DesignSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单个成员卡片 */
@Composable
internal fun MemberCard(
    member: FamilyMember,
    onOpenMap: () -> Unit,
    onSetNote: (String) -> Unit
) {
    // 备注编辑对话框状态
    var editingNote by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf(member.note) }
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
                IconButton(onClick = {
                    // 每次打开都以已保存的备注为初值：否则上次取消编辑时残留的文本
                    // 会在下次打开时冒充内容，点确定就把被放弃的修改存了下去
                    noteText = member.note
                    editingNote = true
                }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.family_edit_note_desc),
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
            // 「获取位置」按钮已移除：进入地图页即自动请求对方位置并定位自己
            androidx.compose.material3.OutlinedButton(
                onClick = onOpenMap,
                shape = RoundedCornerShape(DesignSystem.CornerM),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(DesignSystem.SpacingS))
                Text(stringResource(R.string.family_map))
            }
        }
    }

    // 修改本地备注对话框（仅本机生效，不影响对方）
    if (editingNote) {
        AlertDialog(
            onDismissRequest = { editingNote = false },
            title = { Text(stringResource(R.string.family_edit_note_title)) },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.family_edit_note_label)) },
                    supportingText = { Text(stringResource(R.string.family_edit_note_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetNote(noteText)
                    editingNote = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
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
