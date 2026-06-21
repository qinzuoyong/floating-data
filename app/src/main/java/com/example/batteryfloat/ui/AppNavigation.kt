package com.example.batteryfloat.ui

import android.content.SharedPreferences
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.ui.theme.DesignSystem
import java.io.File

/** 底部导航 Tab 定义 */
private data class NavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val tabs = listOf(
    NavTab("首页", Icons.Filled.Home, Icons.Outlined.Home),
    NavTab("外观", Icons.Filled.Settings, Icons.Outlined.Settings),
    NavTab("关于", Icons.Filled.Info, Icons.Outlined.Info)
)

/**
 * 底部导航主容器
 * 3 Tab：首页 / 外观 / 关于，带 AnimatedContent 页面切换
 * 
 * 重新设计要点：
 * 1. 统一间距：使用 DesignSystem 中的 8dp 网格系统
 * 2. 层级分明：底部导航栏 + 页面内容
 * 3. 美学优化：选中状态、动画切换、触觉反馈
 */
@Composable
fun AppNavigation(
    prefs: SharedPreferences,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenExternalLink: (String, String) -> Unit = { _, _ -> },
    onInstallApk: (File) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = DesignSystem.ElevationNone
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTab = index
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                fontSize = DesignSystem.FontSizeSmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    slideInHorizontally { it * dir } + fadeIn() togetherWith
                            slideOutHorizontally { -it * dir } + fadeOut()
                },
                label = "pageTransition"
            ) { tab ->
                when (tab) {
                    0 -> HomeScreen(
                        prefs = prefs,
                        onStartService = onStartService,
                        onStopService = onStopService,
                        onOpenOverlaySettings = onOpenOverlaySettings
                    )
                    1 -> AppearanceScreen(prefs = prefs)
                    2 -> AboutScreen(
                        prefs = prefs,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onOpenBatterySettings = onOpenBatterySettings,
                        onOpenExternalLink = onOpenExternalLink,
                        onInstallApk = onInstallApk
                    )
                }
            }
        }
    }
}
