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
    primary = SkyBlueDarkPrimary,
    onPrimary = SkyBlueDarkOnPrimary,
    primaryContainer = SkyBlueDarkPrimaryContainer,
    onPrimaryContainer = SkyBlueDarkOnPrimaryContainer,
    secondary = SkyBlueDarkSecondary,
    onSecondary = SkyBlueDarkOnSecondary,
    secondaryContainer = SkyBlueDarkSecondaryContainer,
    onSecondaryContainer = SkyBlueDarkOnSecondaryContainer,
    tertiary = SkyBlueDarkTertiary,
    onTertiary = SkyBlueDarkOnTertiary,
    tertiaryContainer = SkyBlueDarkTertiaryContainer,
    onTertiaryContainer = SkyBlueDarkOnTertiaryContainer,
    background = SkyBlueDarkBackground,
    surface = SkyBlueDarkSurface,
    surfaceVariant = SkyBlueDarkSurfaceVariant,
    onBackground = SkyBlueDarkOnBackground,
    onSurface = SkyBlueDarkOnSurface,
    outline = SkyBlueDarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = SkyBlueLightPrimary,
    onPrimary = SkyBlueLightOnPrimary,
    primaryContainer = SkyBlueLightPrimaryContainer,
    onPrimaryContainer = SkyBlueLightOnPrimaryContainer,
    secondary = SkyBlueLightSecondary,
    onSecondary = SkyBlueLightOnSecondary,
    secondaryContainer = SkyBlueLightSecondaryContainer,
    onSecondaryContainer = SkyBlueLightOnSecondaryContainer,
    tertiary = SkyBlueLightTertiary,
    onTertiary = SkyBlueLightOnTertiary,
    tertiaryContainer = SkyBlueLightTertiaryContainer,
    onTertiaryContainer = SkyBlueLightOnTertiaryContainer,
    background = SkyBlueLightBackground,
    surface = SkyBlueLightSurface,
    surfaceVariant = SkyBlueLightSurfaceVariant,
    onBackground = SkyBlueLightOnBackground,
    onSurface = SkyBlueLightOnSurface,
    outline = SkyBlueLightOutline
)

/**
 * BatteryFloating 主题
 * 优先使用 Android 12+ 动态取色（Monet），回退到天蓝自定义配色
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
