package com.example.batteryfloat.update

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 更新下载对话框
 * 三态界面：发现新版本 → 下载中(进度0%~100%) → 下载完成(立即安装)
 *
 * @param updateVersion 最新版本号
 * @param downloadState 当前下载状态
 * @param onStartDownload 点击"立即升级"的回调
 * @param onInstall 点击"立即安装"的回调
 * @param onDismiss 关闭对话框的回调
 */
@Composable
fun UpdateDownloadDialog(
    updateVersion: String,
    downloadState: DownloadState,
    onStartDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // 下载中不允许关闭
            if (downloadState !is DownloadState.Downloading) onDismiss()
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                // 根据状态显示不同图标
                Text(
                    text = when (downloadState) {
                        is DownloadState.Idle -> "🎉"
                        is DownloadState.Downloading -> "⏳"
                        is DownloadState.Completed -> "✅"
                        is DownloadState.Error -> "❌"
                    },
                    fontSize = 40.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (downloadState) {
                        is DownloadState.Idle -> "发现新版本"
                        is DownloadState.Downloading -> "正在下载…"
                        is DownloadState.Completed -> "下载完成"
                        is DownloadState.Error -> "下载失败"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (downloadState) {
                    is DownloadState.Idle -> {
                        // 提示更新内容
                        Text(
                            text = "v${updateVersion} 现已发布",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "是否立即下载更新？",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    is DownloadState.Downloading -> {
                        // 圆形进度指示器 + 大号百分比
                        val animatedProgress by animateIntAsState(
                            targetValue = downloadState.progress,
                            animationSpec = tween(durationMillis = 300)
                        )
                        Box(
                            modifier = Modifier.size(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { animatedProgress / 100f },
                                modifier = Modifier.size(120.dp),
                                strokeWidth = 8.dp,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeCap = StrokeCap.Round
                            )
                            Text(
                                text = "${animatedProgress}%",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        // 装饰性进度条（线性辅助显示）
                        LinearProgressIndicator(
                            progress = { animatedProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                    is DownloadState.Completed -> {
                        Text(
                            text = "APK 已下载到本地",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "点击下方按钮开始安装",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    is DownloadState.Error -> {
                        Text(
                            text = downloadState.message,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "请检查网络后重试",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                is DownloadState.Idle -> {
                    Button(
                        onClick = onStartDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("立即升级", fontWeight = FontWeight.SemiBold)
                    }
                }
                is DownloadState.Downloading -> {
                    // 下载中只显示进度，按钮不可点击
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("下载中…", fontWeight = FontWeight.SemiBold)
                    }
                }
                is DownloadState.Completed -> {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Text("立即安装", fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
                is DownloadState.Error -> {
                    Button(
                        onClick = {
                            // 重置后重新触发 Idle 状态，用户可再次点击
                            ApkDownloader.reset()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("重新尝试", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        dismissButton = {
            // 下载完成/空闲时显示"以后再说"
            if (downloadState !is DownloadState.Downloading) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = when (downloadState) {
                            is DownloadState.Completed -> "稍后安装"
                            is DownloadState.Error -> "关闭"
                            else -> "以后再说"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
