package com.example.batteryfloat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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