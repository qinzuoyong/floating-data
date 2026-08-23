package com.example.batteryfloat.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.adb.AdbConnectionManager
import kotlinx.coroutines.launch

/**
 * ADB 无线调试配对对话框
 *
 * 引导用户开启无线调试并输入配对码;成功后由调用方置位开关并启动连接。
 * 配对只需一次,之后(含重启)免配对自动重连。
 */
@Composable
fun AdbPairingDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("连接高精度数据源") },
        text = {
            Column {
                Text(
                    "1. 系统设置 → 开发者选项 → 开启「无线调试」\n" +
                            "2. 点进「无线调试」→「使用配对码配对设备」\n" +
                            "3. 输入屏幕显示的 6 位配对码\n\n" +
                            "配对仅需一次,之后重启自动重连。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = input.filter { it.isDigit() }.take(6)
                    },
                    label = { Text("配对码") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (message != null) {
                    Text(
                        message!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && code.length == 6,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val ok = AdbConnectionManager.pair(code)
                        busy = false
                        if (ok) {
                            Toast.makeText(context, "配对成功,正在连接…", Toast.LENGTH_SHORT).show()
                            onSuccess()
                        } else {
                            message = "配对失败:请确认「无线调试」已开启、配对码未过期(每次弹窗刷新)后重试"
                        }
                    }
                }
            ) { Text(if (busy) "配对中…" else "开始配对") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        }
    )
}
