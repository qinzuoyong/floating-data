package com.example.batteryfloat.ui

import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.service.FloatingWindowService

/**
 * 首页 - 悬浮窗控制
 * 包含悬浮窗开关、功耗显示开关、锁定切换、隐藏后台
 */
@Composable
fun HomeScreen(
    prefs: SharedPreferences,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var isServiceRunning by remember { mutableStateOf(FloatingWindowService.isRunning) }
    var lockDrag by remember { mutableStateOf(prefs.getBoolean("lock_drag_enabled", false)) }
    var showPower by remember { mutableStateOf(prefs.getBoolean("show_power", false)) }
    var hideRecents by remember { mutableStateOf(prefs.getBoolean("hide_recents", false)) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题
        Text(
            text = "悬浮窗控制",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        // 悬浮窗开关卡片
        FloatingWindowCard(
            isServiceRunning = isServiceRunning,
            onToggle = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (isServiceRunning) {
                    onStopService()
                    isServiceRunning = false
                    prefs.edit().putBoolean("floating_was_running", false).apply()
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                        onOpenOverlaySettings()
                    } else {
                        onStartService()
                        isServiceRunning = true
                    }
                }
            }
        )

        // 功耗显示（从外观移入首页）
        SettingSwitchCard(
            icon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = "功耗", tint = MaterialTheme.colorScheme.primary) },
            title = "功耗显示",
            subtitle = "开启后悬浮窗显示整机功耗",
            checked = showPower,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                showPower = it
                prefs.edit().putBoolean("show_power", it).apply()
            }
        )

        SettingSwitchCard(
            icon = { Icon(Icons.Filled.Lock, contentDescription = "锁定", tint = MaterialTheme.colorScheme.primary) },
            title = "锁定悬浮窗",
            subtitle = "双击悬浮窗可锁定/解锁位置",
            checked = lockDrag,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lockDrag = it
                prefs.edit().putBoolean("lock_drag_enabled", it).apply()
            }
        )

        SettingSwitchCard(
            icon = { Icon(Icons.Filled.VisibilityOff, contentDescription = "隐藏", tint = MaterialTheme.colorScheme.primary) },
            title = "隐藏后台",
            subtitle = "按 Home/返回键自动隐匿任务卡片",
            checked = hideRecents,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                hideRecents = it
                prefs.edit().putBoolean("hide_recents", it).apply()
            }
        )

        Spacer(Modifier.height(32.dp))
    }
}

/** 悬浮窗开关卡片，带状态指示灯和动画按钮 */
@Composable
private fun FloatingWindowCard(isServiceRunning: Boolean, onToggle: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (isServiceRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        animationSpec = tween(300),
        label = "cardBg"
    )
    val statusColor by animateColorAsState(
        targetValue = if (isServiceRunning) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
        animationSpec = tween(300),
        label = "statusColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("悬浮窗", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isServiceRunning) "运行中" else "已停止",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedToggleButton(isRunning = isServiceRunning, onClick = onToggle)
        }
    }
}

/** 带缩放的开关按钮 */
@Composable
private fun AnimatedToggleButton(isRunning: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "btnScale"
    )
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.scale(scale),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Filled.PowerOff else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (isRunning) "关闭" else "启动",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}