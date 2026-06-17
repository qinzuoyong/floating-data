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
    primary = CyanBlueDarkPrimary,
    onPrimary = CyanBlueDarkOnPrimary,
    primaryContainer = CyanBlueDarkPrimaryContainer,
    onPrimaryContainer = CyanBlueDarkOnPrimaryContainer,
    secondary = CyanBlueDarkSecondary,
    onSecondary = CyanBlueDarkOnSecondary,
    secondaryContainer = CyanBlueDarkSecondaryContainer,
    onSecondaryContainer = CyanBlueDarkOnSecondaryContainer,
    tertiary = CyanBlueDarkTertiary,
    onTertiary = CyanBlueDarkOnTertiary,
    tertiaryContainer = CyanBlueDarkTertiaryContainer,
    onTertiaryContainer = CyanBlueDarkOnTertiaryContainer,
    background = CyanBlueDarkBackground,
    surface = CyanBlueDarkSurface,
    surfaceVariant = CyanBlueDarkSurfaceVariant,
    onBackground = CyanBlueDarkOnBackground,
    onSurface = CyanBlueDarkOnSurface,
    outline = CyanBlueDarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = CyanBlueLightPrimary,
    onPrimary = CyanBlueLightOnPrimary,
    primaryContainer = CyanBlueLightPrimaryContainer,
    onPrimaryContainer = CyanBlueLightOnPrimaryContainer,
    secondary = CyanBlueLightSecondary,
    onSecondary = CyanBlueLightOnSecondary,
    secondaryContainer = CyanBlueLightSecondaryContainer,
    onSecondaryContainer = CyanBlueLightOnSecondaryContainer,
    tertiary = CyanBlueLightTertiary,
    onTertiary = CyanBlueLightOnTertiary,
    tertiaryContainer = CyanBlueLightTertiaryContainer,
    onTertiaryContainer = CyanBlueLightOnTertiaryContainer,
    background = CyanBlueLightBackground,
    surface = CyanBlueLightSurface,
    surfaceVariant = CyanBlueLightSurfaceVariant,
    onBackground = CyanBlueLightOnBackground,
    onSurface = CyanBlueLightOnSurface,
    outline = CyanBlueLightOutline
)

/**
 * BatteryFloating 主题
 * 优先使用 Android 12+ 动态取色（Monet），回退到青蓝色自定义配色
 */
@Composable
fun BatteryFloatingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
