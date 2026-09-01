package com.example.batteryfloat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 受限设置引导对话框
 * Android 14+ 后台启动和悬浮窗权限可能需要在系统设置中手动开启
 * @param onDismiss 关闭回调
 * @param onOpenAppInfo 打开应用信息页回调
 */
@Composable
fun RestrictedSettingsDialog(
    onDismiss: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "需要额外权限",
                fontSize = DesignSystem.FontSizeHeading
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.SpacingS)) {
                Text(
                    text = "Android 14 及以上版本需要手动授权：",
                    fontSize = DesignSystem.FontSizeBody,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(DesignSystem.SpacingXs))
                Text(
                    text = "1. 进入「应用信息」\n2. 找到「显示在其他应用上层」\n3. 允许后台活动",
                    fontSize = DesignSystem.FontSizeCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenAppInfo) {
                Text("去设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}