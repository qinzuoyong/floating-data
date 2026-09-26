package com.example.batteryfloat.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.batteryfloat.adb.AdbAutoGrant
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 自动授权透明卡片
 *
 * 特权通道连通后 AdbAutoGrant 会静默补齐若干高敏权限（安全设置写入、无障碍、悬浮窗、
 * 精确定位与后台定位、电池白名单）。本卡片把这些"自动授予"显式呈现，使授权可见。
 *
 * 只读展示：应用内不提供一键撤销——撤销遍历中撤到运行时权限会被系统强杀进程，
 * 序列执行不完且重连后会被自动授予流程授回；如需撤权，请在系统设置中逐项处理。
 *
 * @param items 已自动授予的条目（含当前实际生效状态）
 * @param modifier 修饰符
 */
@Composable
fun AdbAutoGrantCard(
    items: List<AdbAutoGrant.AutoGrantItem>,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

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
                "由 ADB 特权通道自动补齐，仅作状态展示。",
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
        }
    }
}
