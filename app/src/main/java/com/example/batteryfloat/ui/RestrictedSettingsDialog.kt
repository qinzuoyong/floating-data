package com.example.batteryfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 引导步骤数据类 */
private data class GuideStep(
    val number: Int,
    val icon: ImageVector,
    val title: String,
    val description: String
)

private val steps = listOf(
    GuideStep(
        number = 1,
        icon = Icons.Filled.Security,
        title = "打开应用信息",
        description = "点击下方按钮跳转至「神奇悬浮窗」的应用信息页"
    ),
    GuideStep(
        number = 2,
        icon = Icons.Filled.Security,
        title = "允许受限设置",
        description = "点击右上角 ⋮ 菜单 → 选择「允许受限设置」→ 验证身份"
    ),
    GuideStep(
        number = 3,
        icon = Icons.Filled.ChevronRight,
        title = "返回开启权限",
        description = "操作完成后返回本页，重新尝试启动悬浮窗即可开启"
    )
)

/**
 * Android 14+ 受限设置引导对话框
 *
 * 侧载安装的应用请求 SYSTEM_ALERT_WINDOW 等敏感权限时，Android 14+ 会自动封锁。
 * 需用户在应用信息页的 ⋮ 菜单中「允许受限设置」后才能正常开启。
 * 本对话框提供清晰美观的分步引导。
 *
 * @param onDismiss 关闭对话框
 * @param onOpenAppInfo 打开应用信息页的回调（必须由调用方提供 Context）
 */
@Composable
fun RestrictedSettingsDialog(
    onDismiss: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))

                // 安全盾牌图标（渐变圆形容器）
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 主标题
                Text(
                    text = "需要解除权限限制",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                // 说明文字
                Text(
                    text = "Android 14+ 为保护您侧载安装应用的信息安全，\n自动限制了「悬浮窗权限」功能。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "请按以下 3 步解除限制：",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                steps.forEach { step ->
                    StepRow(step)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onOpenAppInfo()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("打开应用信息", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "以后再说",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    )
}

/** 单步引导行：编号圆点 + 内容 */
@Composable
private fun StepRow(step: GuideStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // 编号圆标（渐变背景）
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${step.number}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(Modifier.width(14.dp))

        // 标题 + 描述
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = step.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
    }
}