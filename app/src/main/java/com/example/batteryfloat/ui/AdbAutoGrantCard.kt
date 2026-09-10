package com.example.batteryfloat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.batteryfloat.adb.AdbAutoGrant
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 自动授权透明卡片
 *
 * 特权通道连通后 AdbAutoGrant 会静默补齐若干高敏权限（安全设置写入、无障碍、悬浮窗、
 * 精确定位与后台定位、电池白名单）。本卡片把这些"自动授予"显式呈现并可一键撤销：
 * 用户对高敏权限的授予与回收必须可见、可控。
 *
 * @param items 已自动授予的条目（含当前实际生效状态）
 * @param onRevoke 确认撤销后的回调（调用方执行撤销并刷新状态）
 * @param modifier 修饰符
 */
@Composable
fun AdbAutoGrantCard(
    items: List<AdbAutoGrant.AutoGrantItem>,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    var confirmRevoke by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(DesignSystem.SpacingM)) {
            Text(
                "已自动授予的权限",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(DesignSystem.SpacingXs))
            Text(
                "由 ADB 特权通道自动补齐，可一键撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(DesignSystem.SpacingS))
            items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DesignSystem.SpacingXs / 2)
                ) {
                    Text(
                        item.kind.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (item.granted) "已生效" else "已失效",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(DesignSystem.SpacingS))
            OutlinedButton(
                onClick = { confirmRevoke = true },
                shape = RoundedCornerShape(DesignSystem.CornerM),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("撤销全部自动授权")
            }
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("撤销自动授权") },
            text = {
                Text(
                    "将撤销上述由本应用自动授予的权限与开关。\n\n" +
                        "家人位置共享、后台保活等能力可能随之失效；" +
                        "需要时可再次连接 ADB 特权通道重新授权。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = false
                    onRevoke()
                }) { Text("撤销") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) { Text("取消") }
            }
        )
    }
}
