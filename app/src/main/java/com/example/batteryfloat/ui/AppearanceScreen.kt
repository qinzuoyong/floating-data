package com.example.batteryfloat.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 预设背景颜色方案 */
private val BG_COLORS = listOf(
    "黑色" to 0xFF000000.toInt(), "深灰" to 0xFF333333.toInt(),
    "灰色" to 0xFF666666.toInt(), "白色" to 0xFFFFFFFF.toInt(),
    "蓝色" to 0xFF1565C0.toInt(), "绿色" to 0xFF2E7D32.toInt(),
    "红色" to 0xFFC62828.toInt()
)

/** 预设字体颜色方案 */
private val TEXT_COLORS = listOf(
    "白色" to 0xFFFFFFFF.toInt(), "黑色" to 0xFF000000.toInt(),
    "黄色" to 0xFFFFEB3B.toInt(), "青色" to 0xFF00BCD4.toInt(),
    "橙色" to 0xFFFF9800.toInt(), "红色" to 0xFFF44336.toInt(),
    "绿色" to 0xFF4CAF50.toInt()
)

/**
 * 外观页面 - 悬浮窗视觉定制
 * 字体大小 / 圆角 / 背景色 / 文字色 / 透明度
 */
@Composable
fun AppearanceScreen(prefs: SharedPreferences) {
    var fontSliderValue by remember { mutableFloatStateOf(prefs.getFloat("font_size", 14f)) }
    var cornerSliderValue by remember { mutableFloatStateOf(prefs.getFloat("corner_radius", 30f)) }
    var bgAlphaValue by remember { mutableFloatStateOf(prefs.getFloat("bg_alpha", 0.8f)) }
    var bgColor by remember { mutableIntStateOf(prefs.getInt("bg_color", 0xFF000000.toInt())) }
    var textColor by remember { mutableIntStateOf(prefs.getInt("text_color", 0xFFFFFFFF.toInt())) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "外观定制",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        // 字体大小
        SliderSettingCard(
            title = "字体大小",
            currentValue = "${fontSliderValue.toInt()} sp",
            value = fontSliderValue,
            valueRange = 1f..30f,
            onValueChange = { fontSliderValue = it; prefs.edit().putFloat("font_size", it).apply() },
            startLabel = "1", midLabel = "15", endLabel = "30"
        )

        // 圆角曲率
        SliderSettingCard(
            title = "圆角曲率",
            currentValue = "${cornerSliderValue.toInt()} px",
            value = cornerSliderValue,
            valueRange = 0f..60f,
            onValueChange = { cornerSliderValue = it; prefs.edit().putFloat("corner_radius", it).apply() },
            startLabel = "0", midLabel = "30", endLabel = "60"
        )

        // 背景颜色
        ColorPickerSection(
            title = "背景颜色",
            colors = BG_COLORS,
            selectedColor = bgColor,
            onColorSelected = { bgColor = it; prefs.edit().putInt("bg_color", it).apply() }
        )

        // 文字颜色
        ColorPickerSection(
            title = "文字颜色",
            colors = TEXT_COLORS,
            selectedColor = textColor,
            onColorSelected = { textColor = it; prefs.edit().putInt("text_color", it).apply() }
        )

        // 透明度
        SliderSettingCard(
            title = "背景透明度",
            currentValue = "${(bgAlphaValue * 100).toInt()}%",
            value = bgAlphaValue,
            valueRange = 0.1f..1f,
            onValueChange = { bgAlphaValue = it; prefs.edit().putFloat("bg_alpha", it).apply() },
            startLabel = "10%", midLabel = "50%", endLabel = "100%"
        )

        Spacer(Modifier.height(32.dp))
    }
}