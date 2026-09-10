package com.example.batteryfloat.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * HomeScreen 的私有子组件（拆分自 HomeScreen.kt，降低单文件行数，逻辑零改动）。
 * 全部为 internal，仅由 HomeScreen 在本包内调用。
 */

/** 特权通道载体单选项行 */
@Composable
internal fun CarrierOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DesignSystem.CornerM))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = DesignSystem.SpacingS, horizontal = DesignSystem.SpacingS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = DesignSystem.SpacingXs)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
internal fun FloatingWindowCard(isServiceRunning: Boolean, onToggle: () -> Unit) {
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
 * 通用动作设置卡片（点击触发动作，无开关状态）
 * 视觉与 SettingSwitchCard 保持一致
 *
 * @param icon Material Icon 组件
 * @param iconBackgroundColor 图标圆形背景色
 * @param title 设置项标题
 * @param subtitle 设置项副标题说明
 * @param onClick 点击回调
 */
@Composable
internal fun SettingActionCard(
    icon: @Composable () -> Unit,
    iconBackgroundColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(DesignSystem.CardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(DesignSystem.SpacingXl + DesignSystem.SpacingXs)
                    .background(iconBackgroundColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(Modifier.width(DesignSystem.SpacingS))
            Column {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    subtitle,
                    fontSize = DesignSystem.FontSizeCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
internal fun AnimatedToggleButton(isRunning: Boolean, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessLow
        ),
        label = "btnScale"
    )

    Button(
        onClick = {
            isPressed = true
            onClick()
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = if (isRunning) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onPrimary
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
