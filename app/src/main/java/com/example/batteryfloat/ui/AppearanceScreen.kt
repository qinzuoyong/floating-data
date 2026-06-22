package com.example.batteryfloat.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.ui.theme.DesignSystem

/** 预设背景颜色方案 */
private val BG_COLORS = listOf(
    "黑色" to 0xFF000000.toInt(), 
    "深灰" to 0xFF333333.toInt(),
    "灰色" to 0xFF666666.toInt(), 
    "白色" to 0xFFFFFFFF.toInt(),
    "蓝色" to 0xFF1565C0.toInt(), 
    "绿色" to 0xFF2E7D32.toInt(),
    "红色" to 0xFFC62828.toInt()
)

/** 预设字体颜色方案 */
private val TEXT_COLORS = listOf(
    "白色" to 0xFFFFFFFF.toInt(), 
    "黑色" to 0xFF000000.toInt(),
    "黄色" to 0xFFFFEB3B.toInt(), 
    "青色" to 0xFF00BCD4.toInt(),
    "橙色" to 0xFFFF9800.toInt(), 
    "红色" to 0xFFF44336.toInt(),
    "绿色" to 0xFF4CAF50.toInt()
)

/**
 * 外观页面 - 悬浮窗视觉定制
 * 
 * 重新设计要点：
 * 1. 统一间距：使用 DesignSystem 中的 8dp 网格系统
 * 2. 层级分明：主题 > 字体 > 圆角 > 颜色 > 透明度
 * 3. 美学优化：分组标题、卡片圆角、交互反馈
 */
@Composable
fun AppearanceScreen(prefs: SharedPreferences) {
    var themeMode by remember { mutableIntStateOf(prefs.getInt(PrefsKeys.THEME_MODE, 0)) }
    var fontSliderValue by remember { mutableFloatStateOf(prefs.getFloat(PrefsKeys.FONT_SIZE, 8f)) }
    var cornerSliderValue by remember { mutableFloatStateOf(prefs.getFloat(PrefsKeys.CORNER_RADIUS, 30f)) }
    var bgAlphaValue by remember { mutableFloatStateOf(prefs.getFloat(PrefsKeys.BG_ALPHA, 0.5f)) }
    var bgColor by remember { mutableIntStateOf(prefs.getInt(PrefsKeys.BG_COLOR, 0xFF666666.toInt())) }
    var textColor by remember { mutableIntStateOf(prefs.getInt(PrefsKeys.TEXT_COLOR, 0xFFFFFFFF.toInt())) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = DesignSystem.PagePadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.SpacingM)
    ) {
        // 标题区域
        PageTitle(
            title = "外观定制",
            modifier = Modifier.padding(top = DesignSystem.SpacingL)
        )

        // ===== 主题设置 =====
        SectionTitle(title = "主题模式")
        ThemeModeCard(
            icon = {
                Icon(
                    Icons.Filled.Palette,
                    contentDescription = "主题",
                    tint = Color(0xFF283593),  // 靛蓝色图标
                    modifier = Modifier.size(24.dp)
                )
            },
            iconBackgroundColor = Color(0xFFE8EAF6),  // 浅靛蓝色背景
            selectedMode = themeMode,
            onModeSelected = { mode ->
                themeMode = mode
                prefs.edit().putInt(PrefsKeys.THEME_MODE, mode).apply()
            }
        )

        // ===== 字体设置 =====
        SectionTitle(title = "字体配置")
        SliderSettingCard(
            icon = {
                Icon(
                    Icons.Filled.TextFields,
                    contentDescription = "字体",
                    tint = Color(0xFF00838F),  // 青色图标
                    modifier = Modifier.size(24.dp)
                )
            },
            iconBackgroundColor = Color(0xFFE0F7FA),  // 浅青色背景
            title = "字体大小",
            currentValue = "${fontSliderValue.toInt()} sp",
            value = fontSliderValue,
            valueRange = 1f..30f,
            onValueChange = { 
                fontSliderValue = it
                prefs.edit().putFloat(PrefsKeys.FONT_SIZE, it).apply() 
            },
            startLabel = "1", 
            midLabel = "15", 
            endLabel = "30"
        )

        // ===== 圆角设置 =====
        SectionTitle(title = "圆角曲率")
        SliderSettingCard(
            icon = {
                Icon(
                    Icons.Filled.CropFree,
                    contentDescription = "圆角",
                    tint = Color(0xFF4E342E),  // 棕色图标
                    modifier = Modifier.size(24.dp)
                )
            },
            iconBackgroundColor = Color(0xFFEFEBE9),  // 浅棕色背景
            title = "圆角大小",
            currentValue = "${cornerSliderValue.toInt()} px",
            value = cornerSliderValue,
            valueRange = 0f..60f,
            onValueChange = { 
                cornerSliderValue = it
                prefs.edit().putFloat(PrefsKeys.CORNER_RADIUS, it).apply() 
            },
            startLabel = "0", 
            midLabel = "30", 
            endLabel = "60"
        )

        // ===== 颜色设置 =====
        SectionTitle(title = "颜色配置")
        ColorPickerSection(
            icon = {
                Icon(
                    Icons.Filled.FormatPaint,
                    contentDescription = "背景色",
                    tint = Color(0xFFAD1457),  // 粉色图标
                    modifier = Modifier.size(24.dp)
                )
            },
            iconBackgroundColor = Color(0xFFFCE4EC),  // 浅粉色背景
            title = "背景颜色",
            colors = BG_COLORS,
            selectedColor = bgColor,
            onColorSelected = { 
                bgColor = it
                prefs.edit().putInt(PrefsKeys.BG_COLOR, it).apply() 
            }
        )

        ColorPickerSection(
            icon = {
                Icon(
                    Icons.Filled.FontDownload,
                    contentDescription = "文字色",
                    tint = Color(0xFF37474F),  // 深灰色图标
                    modifier = Modifier.size(24.dp)
                )
            },
            iconBackgroundColor = Color(0xFFECEFF1),  // 浅灰色背景
            title = "文字颜色",
            colors = TEXT_COLORS,
            selectedColor = textColor,
            onColorSelected = { 
                textColor = it
                prefs.edit().putInt(PrefsKeys.TEXT_COLOR, it).apply() 
            }
        )

        // ===== 透明度设置 =====
        SectionTitle(title = "透明度")
        SliderSettingCard(
            icon = {
                Icon(
                    Icons.Filled.Opacity,
                    contentDescription = "透明度",
                    tint = Color(0xFF455A64),  // 蓝灰色图标
                    modifier = Modifier.size(24.dp)
                )
            },
            iconBackgroundColor = Color(0xFFCFD8DC),  // 浅蓝灰色背景
            title = "背景透明度",
            currentValue = "${(bgAlphaValue * 100).toInt()}%",
            value = bgAlphaValue,
            valueRange = 0.1f..1f,
            onValueChange = { 
                bgAlphaValue = it
                prefs.edit().putFloat(PrefsKeys.BG_ALPHA, it).apply() 
            },
            startLabel = "10%", 
            midLabel = "50%", 
            endLabel = "100%"
        )

        // 底部间距
        Spacer(Modifier.height(DesignSystem.SpacingXl))
    }
}

/**
 * 主题外观选择卡片
 * 
 * 设计特点：
 * 1. 图标带靛蓝色圆形背景（主题/外观）
 * 2. 三段式选择：跟随系统 / 浅色 / 深色
 * 3. 清晰的选中状态
 * 4. 统一的圆角和间距
 */
@Composable
private fun ThemeModeCard(
    icon: @Composable (() -> Unit)? = null,
    iconBackgroundColor: Color = Color.Transparent,
    selectedMode: Int,
    onModeSelected: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(DesignSystem.CardPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 可选的图标带彩色圆形背景
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(iconBackgroundColor),
                        contentAlignment = Alignment.Center
                    ) {
                        icon()
                    }
                    Spacer(modifier = Modifier.width(DesignSystem.SpacingM))
                }
                Text(
                    text = "主题外观",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = DesignSystem.FontSizeBody,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(DesignSystem.SpacingM))
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                val options = listOf("跟随系统", "浅色", "深色")
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        ),
                        onClick = { onModeSelected(index) },
                        selected = selectedMode == index,
                        label = { 
                            Text(
                                label, 
                                fontSize = DesignSystem.FontSizeCaption
                            ) 
                        }
                    )
                }
            }
        }
    }
}
