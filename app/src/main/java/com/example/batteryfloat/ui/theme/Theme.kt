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
    primary = SkyDarkPrimary,
    onPrimary = SkyDarkOnPrimary,
    primaryContainer = SkyDarkPrimaryContainer,
    onPrimaryContainer = SkyDarkOnPrimaryContainer,
    secondary = SkyDarkSecondary,
    onSecondary = SkyDarkOnSecondary,
    secondaryContainer = SkyDarkSecondaryContainer,
    onSecondaryContainer = SkyDarkOnSecondaryContainer,
    tertiary = SkyDarkTertiary,
    onTertiary = SkyDarkOnTertiary,
    tertiaryContainer = SkyDarkTertiaryContainer,
    onTertiaryContainer = SkyDarkOnTertiaryContainer,
    background = SkyDarkBackground,
    surface = SkyDarkSurface,
    surfaceVariant = SkyDarkSurfaceVariant,
    onBackground = SkyDarkOnBackground,
    onSurface = SkyDarkOnSurface,
    outline = SkyDarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = SkyLightPrimary,
    onPrimary = SkyLightOnPrimary,
    primaryContainer = SkyLightPrimaryContainer,
    onPrimaryContainer = SkyLightOnPrimaryContainer,
    secondary = SkyLightSecondary,
    onSecondary = SkyLightOnSecondary,
    secondaryContainer = SkyLightSecondaryContainer,
    onSecondaryContainer = SkyLightOnSecondaryContainer,
    tertiary = SkyLightTertiary,
    onTertiary = SkyLightOnTertiary,
    tertiaryContainer = SkyLightTertiaryContainer,
    onTertiaryContainer = SkyLightOnTertiaryContainer,
    background = SkyLightBackground,
    surface = SkyLightSurface,
    surfaceVariant = SkyLightSurfaceVariant,
    onBackground = SkyLightOnBackground,
    onSurface = SkyLightOnSurface,
    outline = SkyLightOutline
)

/**
 * BatteryFloating 主题
 * 默认使用自定义天蓝浅色配色，关闭动态取色，确保设计样式稳定生效
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
