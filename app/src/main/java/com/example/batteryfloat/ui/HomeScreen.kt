package com.example.batteryfloat.ui

import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.service.FloatingWindowService
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 首页 - 悬浮窗控制
 * 
 * 重新设计要点：
 * 1. 统一间距：使用 DesignSystem 中的 8dp 网格系统
 * 2. 层级分明：主操作区 > 功能开关区 > 隐藏后台区
 * 3. 美学优化：状态指示灯、动画按钮、卡片阴影
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
    var lockDrag by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.LOCK_DRAG_ENABLED, false)) }
    var showPower by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.SHOW_POWER, true)) }
    var hideRecents by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.HIDE_RECENTS, true)) }

    // 页面恢复时刷新服务运行状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceRunning = FloatingWindowService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = DesignSystem.PagePadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.SpacingM)
    ) {
        // 标题区域
        PageTitle(
            title = "悬浮窗控制",
            modifier = Modifier.padding(top = DesignSystem.SpacingL)
        )

        // ===== 主操作区：悬浮窗开关 =====
        FloatingWindowCard(
            isServiceRunning = isServiceRunning,
            onToggle = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (isServiceRunning) {
                    onStopService()
                    isServiceRunning = false
                    prefs.edit().putBoolean(PrefsKeys.FLOATING_WAS_RUNNING, false).apply()
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

        // ===== 功能开关区 =====
        SectionTitle(title = "功能设置")

        // 功耗显示
        SettingSwitchCard(
            icon = { 
                Icon(
                    Icons.AutoMirrored.Filled.ShowChart, 
                    contentDescription = "功耗", 
                    tint = Color(0xFF1A73E8),  // 蓝色图标
                    modifier = Modifier.size(24.dp)
                ) 
            },
            iconBackgroundColor = Color(0xFFE3F2FD),  // 浅蓝色圆形背景
            title = "功耗显示",
            subtitle = "开启后悬浮窗显示整机功耗",
            checked = showPower,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                showPower = it
                prefs.edit().putBoolean(PrefsKeys.SHOW_POWER, it).apply()
            }
        )

        // 锁定悬浮窗
        SettingSwitchCard(
            icon = { 
                Icon(
                    Icons.Filled.Lock, 
                    contentDescription = "锁定", 
                    tint = Color(0xFF7C4DFF),  // 紫色图标
                    modifier = Modifier.size(24.dp)
                ) 
            },
            iconBackgroundColor = Color(0xFFEDE7F6),  // 浅紫色圆形背景
            title = "锁定悬浮窗",
            subtitle = "双击悬浮窗可锁定/解锁位置",
            checked = lockDrag,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lockDrag = it
                prefs.edit().putBoolean(PrefsKeys.LOCK_DRAG_ENABLED, it).apply()
                // 关闭锁定开关时，同时清除实际锁定状态
                if (!it) {
                    prefs.edit().putBoolean(PrefsKeys.LOCK_DRAG_ENGAGED, false).apply()
                }
            }
        )

        // 隐藏后台
        SettingSwitchCard(
            icon = { 
                Icon(
                    Icons.Filled.VisibilityOff, 
                    contentDescription = "隐藏", 
                    tint = Color(0xFFFF6D00),  // 橙色图标
                    modifier = Modifier.size(24.dp)
                ) 
            },
            iconBackgroundColor = Color(0xFFFFF3E0),  // 浅橙色圆形背景
            title = "隐藏后台",
            subtitle = "按 Home/返回键自动隐匿任务卡片",
            checked = hideRecents,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                hideRecents = it
                prefs.edit().putBoolean(PrefsKeys.HIDE_RECENTS, it).apply()
            }
        )

        // 底部间距
        Spacer(Modifier.height(DesignSystem.SpacingXl))
    }
}

/**
 * 悬浮窗开关卡片
 * 
 * 设计特点：
 * 1. 状态指示灯：绿色=运行中，灰色=已停止
 * 2. 动画按钮：带缩放效果
 * 3. 状态文字：清晰的状态描述
 */
@Composable
private fun FloatingWindowCard(isServiceRunning: Boolean, onToggle: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (isServiceRunning) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        animationSpec = tween(DesignSystem.AnimationDurationNormal),
        label = "cardBg"
    )
    
    val statusColor by animateColorAsState(
        targetValue = if (isServiceRunning) {
            Color(0xFF4CAF50)  // 成功绿
        } else {
            Color(0xFFBDBDBD)  // 中性灰
        },
        animationSpec = tween(DesignSystem.AnimationDurationNormal),
        label = "statusColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerXl),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignSystem.CardPaddingLarge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 状态指示灯
                    Box(
                        modifier = Modifier
                            .size(DesignSystem.SpacingS + DesignSystem.SpacingXs)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(DesignSystem.SpacingS))
                    Text(
                        "悬浮窗",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = DesignSystem.FontSizeHeading
                    )
                }
                Spacer(Modifier.height(DesignSystem.SpacingXs))
                Text(
                    if (isServiceRunning) "运行中" else "已停止",
                    fontSize = DesignSystem.FontSizeCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 动画切换按钮
            AnimatedToggleButton(isRunning = isServiceRunning, onClick = onToggle)
        }
    }
}

/**
 * 带缩放动画的切换按钮
 * 
 * 设计特点：
 * 1. 点击时缩放反馈
 * 2. 状态对应颜色：运行=红色停止，停止=绿色启动
 * 3. 图标+文字组合
 */
@Composable
private fun AnimatedToggleButton(isRunning: Boolean, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessLow
        ),
        label = "btnScale"
    )
    
    FilledTonalButton(
        onClick = {
            isPressed = true
            onClick()
        },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        ),
        shape = RoundedCornerShape(DesignSystem.CornerM),
        modifier = Modifier.scale(scale),
        contentPadding = PaddingValues(
            horizontal = DesignSystem.CardPadding,
            vertical = DesignSystem.SpacingS + DesignSystem.SpacingXs
        )
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Filled.PowerOff else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(DesignSystem.FontSizeHeading.value.dp)
        )
        Spacer(Modifier.width(DesignSystem.SpacingS))
        Text(
            if (isRunning) "关闭" else "启动",
            fontWeight = FontWeight.SemiBold,
            fontSize = DesignSystem.FontSizeBody
        )
    }
    
    // 点击后恢复
    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(DesignSystem.AnimationDurationFast.toLong())
            isPressed = false
        }
    }
}
