package com.example.batteryfloat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 页面标题组件
 * @param title 标题文本
 * @param modifier 额外修饰符
 */
@Composable
fun PageTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontSize = DesignSystem.FontSizeTitle,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(DesignSystem.SpacingS))
    }
}

/**
 * 区块标题组件
 * @param title 区块标题文本
 */
@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontSize = DesignSystem.FontSizeHeading,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = DesignSystem.SpacingS)
    )
}

/**
 * 统一高亮主按钮
 * 使用主题主色填充，保证所有主要操作按钮视觉一致
 *
 * @param text 按钮文字
 * @param onClick 点击回调
 * @param modifier 额外修饰符
 * @param icon 可选前置图标
 * @param enabled 是否可点击
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(DesignSystem.CornerM),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(
            horizontal = DesignSystem.CardPadding,
            vertical = DesignSystem.SpacingS + DesignSystem.SpacingXs
        )
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(DesignSystem.SpacingS))
        }
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
            fontSize = DesignSystem.FontSizeBody
        )
    }
}
