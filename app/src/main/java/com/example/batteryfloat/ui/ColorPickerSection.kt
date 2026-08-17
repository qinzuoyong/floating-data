package com.example.batteryfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 颜色选择器组件
 * 用于背景颜色和字体颜色的预设颜色选择
 *
 * @param title 组件标题
 * @param colors 颜色列表（名称 to ARGB Int）
 * @param selectedColor 当前选中的颜色值
 * @param onColorSelected 颜色选择回调
 */
@Composable
fun ColorPickerSection(
    title: String,
    colors: List<Pair<String, Int>>,
    selectedColor: Int,
    onColorSelected: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(DesignSystem.CardPadding)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(DesignSystem.SpacingS))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.SpacingS)
            ) {
                colors.forEach { (name, colorInt) ->
                    val selected = selectedColor == colorInt
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(IntrinsicSize.Min)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(DesignSystem.SpacingXl + DesignSystem.SpacingXs)
                                .clip(CircleShape)
                                .background(Color(colorInt))
                                .let { modifier ->
                                    if (selected) {
                                        modifier.border(
                                            3.dp,
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        )
                                    } else modifier
                                }
                                .clickable { onColorSelected(colorInt) }
                        )
                        Spacer(Modifier.height(DesignSystem.SpacingXs))
                        Text(
                            name,
                            fontSize = DesignSystem.FontSizeCaption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
