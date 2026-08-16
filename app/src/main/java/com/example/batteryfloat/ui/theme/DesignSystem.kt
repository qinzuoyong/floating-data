package com.example.batteryfloat.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween

/**
 * 设计系统 - 统一管理 UI 间距、圆角、字号、动画等设计令牌
 * 基于 8dp 网格系统，确保视觉一致性
 */
object DesignSystem {
    // ===== 间距（8dp 网格） =====
    val SpacingXs = 4.dp
    val SpacingS = 8.dp
    val SpacingM = 16.dp
    val SpacingL = 24.dp
    val SpacingXl = 32.dp

    // ===== 页面/卡片内边距 =====
    val PagePadding = 16.dp
    val CardPadding = 16.dp
    val CardPaddingLarge = 20.dp

    // ===== 圆角 =====
    val CornerS = 8.dp
    val CornerM = 12.dp
    val CornerL = 16.dp
    val CornerXl = 24.dp

    // ===== 字号 =====
    val FontSizeCaption = 12.sp
    val FontSizeBody = 14.sp
    val FontSizeHeading = 18.sp
    val FontSizeTitle = 24.sp

    // ===== 动画时长（毫秒） =====
    const val AnimationDurationFast = 150
    const val AnimationDurationNormal = 300

    // ===== 阴影 =====
    val ElevationNone = 0.dp
}