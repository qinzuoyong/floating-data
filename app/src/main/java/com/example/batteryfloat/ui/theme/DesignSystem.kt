package com.example.batteryfloat.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * BatteryFloating 设计规范系统
 * 统一间距、圆角、字体等设计变量
 * 
 * 设计原则：
 * 1. 先锁节奏：统一使用8dp网格系统
 * 2. 再锁变量：定义颜色、字体、圆角等变量
 * 3. 最后锁层级：明确组件层级关系
 */
object DesignSystem {
    
    // ===== 节奏系统（Rhythm）=====
    // 基础单位：8dp
    val SpacingXs = 4.dp      // 最小间距
    val SpacingS = 8.dp       // 小间距
    val SpacingM = 16.dp      // 中间距
    val SpacingL = 24.dp      // 大间距
    val SpacingXl = 32.dp     // 超大间距
    val SpacingXxl = 48.dp    // 特大间距
    
    // 页面边距
    val PagePadding = 16.dp
    
    // 卡片内边距
    val CardPadding = 16.dp
    val CardPaddingLarge = 20.dp
    
    // ===== 圆角系统（Corner Radius）=====
    val CornerS = 8.dp        // 小圆角（按钮、输入框）
    val CornerM = 12.dp       // 中圆角（卡片）
    val CornerL = 16.dp       // 大圆角（大卡片）
    val CornerXl = 24.dp      // 超大圆角（特殊卡片）
    val CornerFull = 100.dp   // 全圆角（圆形）
    
    // ===== 字体系统（Typography）=====
    val FontSizeTitle = 24.sp
    val FontSizeHeading = 18.sp
    val FontSizeBody = 14.sp
    val FontSizeCaption = 12.sp
    val FontSizeSmall = 11.sp
    
    // ===== 动画系统（Animation）=====
    const val AnimationDurationFast = 150
    const val AnimationDurationNormal = 300
    const val AnimationDurationSlow = 500
    
    // ===== 阴影系统（Elevation）=====
    val ElevationNone = 0.dp
    val ElevationLow = 2.dp
    val ElevationMedium = 4.dp
    val ElevationHigh = 8.dp
}
