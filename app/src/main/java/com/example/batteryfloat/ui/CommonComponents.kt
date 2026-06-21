package com.example.batteryfloat.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 通用卡片容器
 * 提供统一的卡片样式和间距
 */
@Composable
fun SettingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(DesignSystem.CardPadding),
            content = content
        )
    }
}

/**
 * 带图标的设置开关卡片
 * 
 * 设计特点：
 * 1. 图标带彩色圆形背景，增强视觉识别性
 * 2. 卡片使用不透明的 surfaceContainerLow 背景
 * 3. 统一的间距和圆角
 */
@Composable
fun SettingSwitchCard(
    icon: @Composable () -> Unit,
    iconBackgroundColor: Color = Color.Transparent,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignSystem.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // 图标带彩色圆形背景
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(iconBackgroundColor),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
                Spacer(modifier = Modifier.width(DesignSystem.SpacingM))
                Column {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = DesignSystem.FontSizeBody,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        fontSize = DesignSystem.FontSizeCaption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/**
 * 带滑块的设置卡片
 * 
 * 设计特点：
 * 1. 图标带彩色圆形背景，增强视觉识别性
 * 2. 卡片使用不透明的 surfaceContainerLow 背景
 * 3. 统一的间距和圆角
 */
@Composable
fun SliderSettingCard(
    icon: @Composable (() -> Unit)? = null,
    iconBackgroundColor: Color = Color.Transparent,
    title: String,
    currentValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    startLabel: String,
    midLabel: String,
    endLabel: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(DesignSystem.CardPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 可选的图标带彩色圆形背景
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(iconBackgroundColor),
                            contentAlignment = Alignment.Center
                        ) {
                            icon()
                        }
                        Spacer(modifier = Modifier.width(DesignSystem.SpacingM))
                    }
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = DesignSystem.FontSizeBody,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = currentValue,
                    fontSize = DesignSystem.FontSizeCaption,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(DesignSystem.SpacingS))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = startLabel,
                    fontSize = DesignSystem.FontSizeSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = midLabel,
                    fontSize = DesignSystem.FontSizeSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = endLabel,
                    fontSize = DesignSystem.FontSizeSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 颜色选择器卡片
 * 
 * 设计特点：
 * 1. 图标带彩色圆形背景，增强视觉识别性
 * 2. 卡片使用不透明的 surfaceContainerLow 背景
 * 3. 统一的间距和圆角
 */
@Composable
fun ColorPickerSection(
    icon: @Composable (() -> Unit)? = null,
    iconBackgroundColor: Color = Color.Transparent,
    title: String,
    colors: List<Pair<String, Int>>,
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(DesignSystem.CardPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 可选的图标带彩色圆形背景
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(iconBackgroundColor),
                        contentAlignment = Alignment.Center
                    ) {
                        icon()
                    }
                    Spacer(modifier = Modifier.width(DesignSystem.SpacingM))
                }
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = DesignSystem.FontSizeBody,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(DesignSystem.SpacingM))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.SpacingS)
            ) {
                colors.forEach { (name, colorInt) ->
                    val selected = selectedColor == colorInt
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(colorInt))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onColorSelected(colorInt) }
                            .then(
                                if (selected) {
                                    Modifier.padding(4.dp)
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "已选择",
                                tint = if (isColorLight(colorInt)) Color.Black else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 判断颜色是否为浅色
 */
private fun isColorLight(color: Int): Boolean {
    val red = (color shr 16) and 0xFF
    val green = (color shr 8) and 0xFF
    val blue = color and 0xFF
    val brightness = (red * 299 + green * 587 + blue * 114) / 1000
    return brightness > 128
}

/**
 * 页面标题
 */
@Composable
fun PageTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontSize = DesignSystem.FontSizeTitle,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(
            top = DesignSystem.SpacingS,
            bottom = DesignSystem.SpacingS
        )
    )
}

/**
 * 分组标题
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontSize = DesignSystem.FontSizeCaption,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = DesignSystem.SpacingS,
            top = DesignSystem.SpacingM,
            bottom = DesignSystem.SpacingS
        )
    )
}
