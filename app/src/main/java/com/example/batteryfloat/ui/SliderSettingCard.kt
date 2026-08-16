package com.example.batteryfloat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 滑块设置卡片
 * 用于字体大小、圆角曲率、透明度等滑块调节，无阴影扁平设计
 *
 * @param title 设置项标题
 * @param currentValue 当前值的文本显示
 * @param value 滑块当前数值
 * @param valueRange 滑块取值范围
 * @param onValueChange 数值变化回调
 * @param startLabel 范围起始标签
 * @param midLabel 范围中间标签
 * @param endLabel 范围结束标签
 */
@Composable
fun SliderSettingCard(
    title: String,
    currentValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    startLabel: String = "",
    midLabel: String = "",
    endLabel: String = ""
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
            Spacer(Modifier.height(DesignSystem.SpacingXs))
            Text(
                currentValue,
                fontSize = DesignSystem.FontSizeBody,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth()
            )
            if (startLabel.isNotEmpty() || midLabel.isNotEmpty() || endLabel.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(startLabel, fontSize = DesignSystem.FontSizeCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(midLabel, fontSize = DesignSystem.FontSizeCaption, color = MaterialTheme.colorScheme.outline)
                    Text(endLabel, fontSize = DesignSystem.FontSizeCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
