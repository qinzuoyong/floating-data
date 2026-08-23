package com.example.batteryfloat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.adb.AdbConnectionManager

/**
 * ADB 配对引导对话框
 *
 * 配对输码在通知栏完成(RemoteInput 直回,与系统配对弹窗同屏可见,免分屏):
 * 「开始配对」启动 AdbPairingService(持续 NSD 发现端口)并跳转开发者设置。
 */
@Composable
fun AdbPairingDialog(
    onDismiss: () -> Unit,
    onOpenDevSettings: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接高精度数据源") },
        text = {
            Column {
                Text(
                    "1. 前往开发者选项,开启「无线调试」\n" +
                            "2. 点「开始配对」跳转设置,进入「无线调试 → 使用配对码配对设备」\n" +
                            "3. 下拉通知栏,点「输入配对码」,直接回复系统弹窗显示的 6 位数字\n\n",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                SelectionContainer {
                    Text(
                        "通知栏与系统配对弹窗同屏可见,无需切换应用。\n配对仅需一次,之后重启自动重连。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AdbConnectionManager.startPairingService(context)
                onOpenDevSettings()
                onDismiss()
            }) { Text("开始配对") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
