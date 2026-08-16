package com.example.batteryfloat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = WarmDarkPrimary,
    onPrimary = WarmDarkOnPrimary,
    primaryContainer = WarmDarkPrimaryContainer,
    onPrimaryContainer = WarmDarkOnPrimaryContainer,
    secondary = WarmDarkSecondary,
    onSecondary = WarmDarkOnSecondary,
    secondaryContainer = WarmDarkSecondaryContainer,
    onSecondaryContainer = WarmDarkOnSecondaryContainer,
    tertiary = WarmDarkTertiary,
    onTertiary = WarmDarkOnTertiary,
    tertiaryContainer = WarmDarkTertiaryContainer,
    onTertiaryContainer = WarmDarkOnTertiaryContainer,
    background = WarmDarkBackground,
    surface = WarmDarkSurface,
    surfaceVariant = WarmDarkSurfaceVariant,
    onBackground = WarmDarkOnBackground,
    onSurface = WarmDarkOnSurface,
    outline = WarmDarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = WarmLightPrimary,
    onPrimary = WarmLightOnPrimary,
    primaryContainer = WarmLightPrimaryContainer,
    onPrimaryContainer = WarmLightOnPrimaryContainer,
    secondary = WarmLightSecondary,
    onSecondary = WarmLightOnSecondary,
    secondaryContainer = WarmLightSecondaryContainer,
    onSecondaryContainer = WarmLightOnSecondaryContainer,
    tertiary = WarmLightTertiary,
    onTertiary = WarmLightOnTertiary,
    tertiaryContainer = WarmLightTertiaryContainer,
    onTertiaryContainer = WarmLightOnTertiaryContainer,
    background = WarmLightBackground,
    surface = WarmLightSurface,
    surfaceVariant = WarmLightSurfaceVariant,
    onBackground = WarmLightOnBackground,
    onSurface = WarmLightOnSurface,
    outline = WarmLightOutline
)

/**
 * BatteryFloating 主题
 * 默认使用自定义暖色配色，关闭动态取色，确保设计样式稳定生效
 */
@Composable
fun BatteryFloatingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    themeMode: Int = 0,
    content: @Composable () -> Unit
) {
    val effectiveDark = when (themeMode) {
        1 -> false  // 浅色
        2 -> true   // 深色
        else -> darkTheme  // 跟随系统
    }
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        effectiveDark -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
