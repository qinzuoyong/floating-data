package com.example.batteryfloat.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.PrefsKeys

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
 * 主题外观 / 字体大小 / 圆角 / 背景色 / 文字色 / 透明度
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

        // 主题外观选择
        ThemeModeCard(
            selectedMode = themeMode,
            onModeSelected = { mode ->
                themeMode = mode
                prefs.edit().putInt(PrefsKeys.THEME_MODE, mode).apply()
            }
        )

        // 字体大小
        SliderSettingCard(
            title = "字体大小",
            currentValue = "${fontSliderValue.toInt()} sp",
            value = fontSliderValue,
            valueRange = 1f..30f,
            onValueChange = { fontSliderValue = it; prefs.edit().putFloat(PrefsKeys.FONT_SIZE, it).apply() },
            startLabel = "1", midLabel = "15", endLabel = "30"
        )

        // 圆角曲率
        SliderSettingCard(
            title = "圆角曲率",
            currentValue = "${cornerSliderValue.toInt()} px",
            value = cornerSliderValue,
            valueRange = 0f..60f,
            onValueChange = { cornerSliderValue = it; prefs.edit().putFloat(PrefsKeys.CORNER_RADIUS, it).apply() },
            startLabel = "0", midLabel = "30", endLabel = "60"
        )

        // 背景颜色
        ColorPickerSection(
            title = "背景颜色",
            colors = BG_COLORS,
            selectedColor = bgColor,
            onColorSelected = { bgColor = it; prefs.edit().putInt(PrefsKeys.BG_COLOR, it).apply() }
        )

        // 文字颜色
        ColorPickerSection(
            title = "文字颜色",
            colors = TEXT_COLORS,
            selectedColor = textColor,
            onColorSelected = { textColor = it; prefs.edit().putInt(PrefsKeys.TEXT_COLOR, it).apply() }
        )

        // 透明度
        SliderSettingCard(
            title = "背景透明度",
            currentValue = "${(bgAlphaValue * 100).toInt()}%",
            value = bgAlphaValue,
            valueRange = 0.1f..1f,
            onValueChange = { bgAlphaValue = it; prefs.edit().putFloat(PrefsKeys.BG_ALPHA, it).apply() },
            startLabel = "10%", midLabel = "50%", endLabel = "100%"
        )

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * 主题外观选择卡片
 * 提供三个选项：跟随系统 / 浅色 / 深色
 */
@Composable
private fun ThemeModeCard(
    selectedMode: Int,
    onModeSelected: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "主题外观",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(12.dp))
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
                        label = { Text(label, fontSize = 13.sp) }
                    )
                }
            }
        }
    }
}