package com.example.batteryfloat

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.batteryfloat.service.FloatingWindowService
import com.example.batteryfloat.ui.theme.BatteryFloatingTheme
import com.example.batteryfloat.update.UpdateChecker

class MainActivity : ComponentActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("floating_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BatteryFloatingTheme {
                MainScreen(
                    prefs = prefs,
                    onStartService = { FloatingWindowService.start(this) },
                    onStopService = { FloatingWindowService.stop(this) },
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onOpenBatterySettings = { openBatteryOptimizationSettings() },
                    onOpenUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                )
            }
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.getBoolean("hide_recents", false)) {
            Log.i("MainActivity", "隐藏后台(Home键): finishAndRemoveTask")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && prefs.getBoolean("hide_recents", false)) {
            Log.i("MainActivity", "隐藏后台(返回键): finishAndRemoveTask")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

// 预设背景颜色方案（带中文名）
private val BG_COLORS = listOf(
    "黑色" to 0xFF000000.toInt(),
    "深灰" to 0xFF333333.toInt(),
    "灰色" to 0xFF666666.toInt(),
    "白色" to 0xFFFFFFFF.toInt(),
    "蓝色" to 0xFF1565C0.toInt(),
    "绿色" to 0xFF2E7D32.toInt(),
    "红色" to 0xFFC62828.toInt(),
)

// 预设字体颜色方案（带中文名）
private val TEXT_COLORS = listOf(
    "白色" to 0xFFFFFFFF.toInt(),
    "黑色" to 0xFF000000.toInt(),
    "黄色" to 0xFFFFEB3B.toInt(),
    "青色" to 0xFF00BCD4.toInt(),
    "橙色" to 0xFFFF9800.toInt(),
    "红色" to 0xFFF44336.toInt(),
    "绿色" to 0xFF4CAF50.toInt(),
)

@Composable
fun MainScreen(
    prefs: SharedPreferences,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current

    var fontSliderValue by remember { mutableFloatStateOf(prefs.getFloat("font_size", 14f)) }
    var cornerSliderValue by remember { mutableFloatStateOf(prefs.getFloat("corner_radius", 30f)) }
    var bgAlphaValue by remember { mutableFloatStateOf(prefs.getFloat("bg_alpha", 0.8f)) }
    var isServiceRunning by remember { mutableStateOf(FloatingWindowService.isRunning) }
    var bgColor by remember { mutableIntStateOf(prefs.getInt("bg_color", 0xFF000000.toInt())) }
    var textColor by remember { mutableIntStateOf(prefs.getInt("text_color", 0xFFFFFFFF.toInt())) }
    var showPower by remember { mutableStateOf(prefs.getBoolean("show_power", false)) }
    var hideRecents by remember { mutableStateOf(prefs.getBoolean("hide_recents", false)) }
    var lockDrag by remember { mutableStateOf(prefs.getBoolean("lock_drag_enabled", false)) }

    // ===== 版本更新检测 =====
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateVersion by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var hasChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasChecked) {
            hasChecked = true
            val info = UpdateChecker.check("1.5")
            if (info.hasUpdate) {
                updateVersion = info.latestVersion
                updateUrl = info.downloadUrl
                showUpdateDialog = true
            }
        }
    }

    // ===== 版本更新弹窗 =====
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "🎉", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("发现新版本", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "v${updateVersion} 现已发布",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                    "当前版本: v1.5",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "是否前往下载页面更新？",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showUpdateDialog = false; onOpenUrl(updateUrl) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("立即升级", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUpdateDialog = false },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                ) { Text("以后再说", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ===== 标题 =====
        Text(
            text = "🔋 电池温度悬浮窗",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            textAlign = TextAlign.Center
        )

        // ===== 悬浮窗开关 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🪟", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("悬浮窗", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                Button(
                    onClick = {
                        if (isServiceRunning) { onStopService(); isServiceRunning = false }
                        else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                                onOpenOverlaySettings()
                            } else { onStartService(); isServiceRunning = true }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (isServiceRunning) "关闭" else "启动") }
            }
        }

        // ===== 锁定悬浮窗 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔒", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("锁定悬浮窗", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                    Text("双击悬浮窗可锁定/解锁位置", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = lockDrag, onCheckedChange = {
                    lockDrag = it
                    prefs.edit().putBoolean("lock_drag_enabled", it).apply()
                })
            }
        }

        // ===== 功耗显示 =====
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("⚡ 功耗显示", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("开启后悬浮窗显示整机功耗", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = showPower, onCheckedChange = {
                    showPower = it
                    prefs.edit().putBoolean("show_power", it).apply()
                })
            }
        }

        // ===== 隐藏后台 =====
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("👻 隐藏后台", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("返回桌面后自动隐藏任务卡片", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = hideRecents, onCheckedChange = {
                    hideRecents = it
                    prefs.edit().putBoolean("hide_recents", it).apply()
                })
            }
        }

        // ===== 字体大小 =====
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🔤 字体大小", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("${fontSliderValue.toInt()} sp", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium)
                Slider(value = fontSliderValue, onValueChange = {
                    fontSliderValue = it; prefs.edit().putFloat("font_size", it).apply()
                }, valueRange = 1f..30f, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("15", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text("30", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ===== 圆角曲率 =====
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⬟ 圆角曲率", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("${cornerSliderValue.toInt()} dp", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium)
                Slider(value = cornerSliderValue, onValueChange = {
                    cornerSliderValue = it; prefs.edit().putFloat("corner_radius", it).apply()
                }, valueRange = 0f..50f, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("25", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text("50", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ===== 背景颜色 =====
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🎨 背景颜色", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BG_COLORS.forEach { (name, colorInt) ->
                        val selected = bgColor == colorInt
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(IntrinsicSize.Min)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape)
                                    .background(Color(colorInt))
                                    .let { if (selected) it.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else it }
                                    .clickable {
                                        bgColor = colorInt
                                        prefs.edit().putInt("bg_color", colorInt).apply()
                                    }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ===== 字体颜色 =====
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("✏️ 字体颜色", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_COLORS.forEach { (name, colorInt) ->
                        val selected = textColor == colorInt
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(IntrinsicSize.Min)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape)
                                    .background(Color(colorInt))
                                    .let { if (selected) it.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else it }
                                    .clickable {
                                        textColor = colorInt
                                        prefs.edit().putInt("text_color", colorInt).apply()
                                    }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ===== 透明度 =====
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("👁️ 透明度", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("${(bgAlphaValue * 100).toInt()}%", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium)
                Slider(value = bgAlphaValue, onValueChange = {
                    bgAlphaValue = it; prefs.edit().putFloat("bg_alpha", it).apply()
                }, valueRange = 0.1f..1f, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("10%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("50%", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text("100%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ===== 用户提示 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⚠️ 用户必读", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.height(4.dp))
                Text("请前往「手机管家(各品牌名称可能不同,例如i管家) → 权限管理 → 自启动」允许本应用自启动，并在「电池 → 后台耗电管理」中设为「允许后台高耗电」，否则悬浮窗可能被系统清理。",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.height(6.dp))
                Text("如果系统权限受限制，请到应用信息中解除，例如iQOO操作步骤：桌面长按应用 → 应用信息 → 点击右上角解除。",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }

        // ===== 权限引导 =====
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🔐 权限引导", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onOpenOverlaySettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("开启悬浮窗权限") }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("忽略电池优化") }
            }
        }

        // ===== 检查更新 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("🎯 版本更新", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("v1.5", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            if (!isChecking) {
                                isChecking = true
                                scope.launch {
            val info = UpdateChecker.check("1.5")
                                    isChecking = false
                                    if (info.hasUpdate) {
                                        updateVersion = info.latestVersion
                                        updateUrl = info.downloadUrl
                                        showUpdateDialog = true
                                    } else {
                                        Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = !isChecking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (isChecking) "检查中…" else "检查更新", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
