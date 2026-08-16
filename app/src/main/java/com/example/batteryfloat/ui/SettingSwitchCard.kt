package com.example.batteryfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 通用开关设置卡片
 * 使用 Material Icon 图标，无阴影扁平设计
 *
 * @param icon Material Icon 组件
 * @param iconBackgroundColor 图标圆形背景色
 * @param title 设置项标题
 * @param subtitle 设置项副标题说明
 * @param checked 当前开关状态
 * @param onCheckedChange 开关状态变化回调
 */
@Composable
fun SettingSwitchCard(
    icon: @Composable () -> Unit,
    iconBackgroundColor: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
                .padding(DesignSystem.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(DesignSystem.SpacingXl + DesignSystem.SpacingXs)
                            .background(iconBackgroundColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        icon()
                    }
                    Spacer(Modifier.width(DesignSystem.SpacingS))
                    Text(
                        title,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    subtitle,
                    fontSize = DesignSystem.FontSizeCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = DesignSystem.SpacingXl + DesignSystem.SpacingS + DesignSystem.SpacingXs,
                        top = DesignSystem.SpacingXs
                    )
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
