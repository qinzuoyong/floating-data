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
import com.example.batteryfloat.service.KeepliveA11yService
import com.example.batteryfloat.ui.theme.BatteryFloatingTheme
import com.example.batteryfloat.util.KeepaliveManager
import androidx.lifecycle.Lifecycle
import com.example.batteryfloat.update.ApkDownloader
import com.example.batteryfloat.update.DownloadState
import com.example.batteryfloat.update.UpdateChecker
import com.example.batteryfloat.update.UpdateDownloadDialog
import com.example.batteryfloat.ui.SettingSwitchCard
import com.example.batteryfloat.ui.ColorPickerSection
import com.example.batteryfloat.ui.SliderSettingCard

class MainActivity : ComponentActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("floating_prefs", Context.MODE_PRIVATE)
    }

    /** 标志位：是否正在启动外部 Intent（如安装 APK/权限设置），跳过 onUserLeaveHint 的 finishAndRemoveTask */
    @Volatile
    private var isLaunchingExternal = false

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
                    onInstallApk = { file ->
                        isLaunchingExternal = true
                        ApkDownloader.install(this, file)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从外部页面返回后重置标志
        isLaunchingExternal = false
        // 同步保活服务的真实运行状态（返回值无实际用途，仅触发状态同步）
        KeepaliveManager.isKeepaliveRunning()
    }

    private fun openOverlaySettings() {
        isLaunchingExternal = true
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isLaunchingExternal = true
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 如果是启动外部 Intent（安装 APK/权限设置）导致的离开，不执行隐藏后台
        if (isLaunchingExternal) {
            Log.d("MainActivity", "onUserLeaveHint: 启动外部 Intent，跳过 finishAndRemoveTask")
            return
        }
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

// ==============================
// 颜色预设方案
// ==============================

/** 预设背景颜色方案（带中文名） */
private val BG_COLORS = listOf(
    "黑色" to 0xFF000000.toInt(),
    "深灰" to 0xFF333333.toInt(),
    "灰色" to 0xFF666666.toInt(),
    "白色" to 0xFFFFFFFF.toInt(),
    "蓝色" to 0xFF1565C0.toInt(),
    "绿色" to 0xFF2E7D32.toInt(),
    "红色" to 0xFFC62828.toInt(),
)

/** 预设字体颜色方案（带中文名） */
private val TEXT_COLORS = listOf(
    "白色" to 0xFFFFFFFF.toInt(),
    "黑色" to 0xFF000000.toInt(),
    "黄色" to 0xFFFFEB3B.toInt(),
    "青色" to 0xFF00BCD4.toInt(),
    "橙色" to 0xFFFF9800.toInt(),
    "红色" to 0xFFF44336.toInt(),
    "绿色" to 0xFF4CAF50.toInt(),
)

// ==============================
// 主屏幕 Composable
// ==============================

/**
 * 主设置界面
 * 按功能模块拆分为独立子组件，保持每个组件职责单一
 */
@Composable
fun MainScreen(
    prefs: SharedPreferences,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onInstallApk: (java.io.File) -> Unit = {}
) {
    val context = LocalContext.current

    // ===== 持久化设置状态 =====
    var fontSliderValue by remember { mutableFloatStateOf(prefs.getFloat("font_size", 14f)) }
    var cornerSliderValue by remember { mutableFloatStateOf(prefs.getFloat("corner_radius", 30f)) }
    var bgAlphaValue by remember { mutableFloatStateOf(prefs.getFloat("bg_alpha", 0.8f)) }
    var isServiceRunning by remember { mutableStateOf(FloatingWindowService.isRunning) }
    var bgColor by remember { mutableIntStateOf(prefs.getInt("bg_color", 0xFF000000.toInt())) }
    var textColor by remember { mutableIntStateOf(prefs.getInt("text_color", 0xFFFFFFFF.toInt())) }
    var showPower by remember { mutableStateOf(prefs.getBoolean("show_power", false)) }
    var hideRecents by remember { mutableStateOf(prefs.getBoolean("hide_recents", false)) }
    var lockDrag by remember { mutableStateOf(prefs.getBoolean("lock_drag_enabled", false)) }
    var keepaliveEnabled by remember { mutableStateOf(prefs.getBoolean("keepalive_enabled", false)) }
    var bootAutoStart by remember { mutableStateOf(prefs.getBoolean("boot_auto_start", true)) }

    // ===== 版本更新状态 =====
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateVersion by remember { mutableStateOf("") }
    var updateApkUrl by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var hasChecked by remember { mutableStateOf(false) }

    // 观察 ApkDownloader 的下载状态
    val downloadState by ApkDownloader.downloadState.collectAsState()

    // ===== 生命周期观察：回到前台时同步保活与悬浮窗状态 =====
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                keepaliveEnabled = KeepliveA11yService.isRunning
                isServiceRunning = FloatingWindowService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ===== 版本更新检测（仅首次启动时） =====
    LaunchedEffect(Unit) {
        if (!hasChecked) {
            hasChecked = true
            val info = UpdateChecker.check("1.55")
            if (info.hasUpdate) {
                updateVersion = info.latestVersion
                updateApkUrl = info.apkDownloadUrl
                showUpdateDialog = true
            }
        }
    }

    // ===== 下载状态变化时自动打开对话框 =====
    LaunchedEffect(downloadState) {
        when (downloadState) {
            is DownloadState.Downloading, is DownloadState.Completed, is DownloadState.Error -> showUpdateDialog = true
            else -> {}
        }
    }

    // ===== 更新下载对话框 =====
    if (showUpdateDialog) {
        UpdateDownloadDialog(
            updateVersion = updateVersion,
            downloadState = downloadState,
            onStartDownload = {
                if (updateApkUrl.isNotBlank()) {
                    scope.launch { ApkDownloader.download(context, updateApkUrl) }
                }
            },
            onInstall = {
                val completedState = downloadState as? DownloadState.Completed
                if (completedState != null) onInstallApk(completedState.file)
            },
            onDismiss = {
                showUpdateDialog = false
                if (downloadState is DownloadState.Completed) ApkDownloader.cleanup(context)
            }
        )
    }

    // ===== 主界面滚动布局 =====
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleSection()

        FloatingWindowCard(
            isServiceRunning = isServiceRunning,
            onToggle = {
                if (isServiceRunning) {
                    onStopService()
                    isServiceRunning = false
                    prefs.edit().putBoolean("floating_was_running", false).apply()
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                        onOpenOverlaySettings()
                    } else {
                        onStartService()
                        isServiceRunning = true
                    }
                }
            }
        )

        SettingSwitchCard(
            icon = "🔒",
            title = "锁定悬浮窗",
            subtitle = "双击悬浮窗可锁定/解锁位置",
            checked = lockDrag,
            onCheckedChange = {
                lockDrag = it
                prefs.edit().putBoolean("lock_drag_enabled", it).apply()
            }
        )

        SettingSwitchCard(
            icon = "⚡",
            title = "功耗显示",
            subtitle = "开启后悬浮窗显示整机功耗",
            checked = showPower,
            onCheckedChange = {
                showPower = it
                prefs.edit().putBoolean("show_power", it).apply()
            }
        )

        SettingSwitchCard(
            icon = "🙈",
            title = "隐藏后台",
            subtitle = "按Home/返回键自动隐匿任务卡片",
            checked = hideRecents,
            onCheckedChange = {
                hideRecents = it
                prefs.edit().putBoolean("hide_recents", it).apply()
            }
        )

        KeepaliveCard(
            keepaliveEnabled = keepaliveEnabled,
            onToggle = { enabled ->
                keepaliveEnabled = enabled
                scope.launch {
                    val success = KeepaliveManager.toggleKeepalive(context, enabled)
                    if (!success) {
                        keepaliveEnabled = !enabled
                        Log.w("MainActivity", "保活操作失败，开关恢复")
                    } else {
                        prefs.edit().putBoolean("keepalive_enabled", enabled).apply()
                    }
                }
            }
        )

        SettingSwitchCard(
            icon = "🚀",
            title = "开机自启动",
            subtitle = if (bootAutoStart) "开机后智能判断悬浮窗状态" else "已关闭",
            checked = bootAutoStart,
            onCheckedChange = {
                bootAutoStart = it
                prefs.edit().putBoolean("boot_auto_start", it).apply()
            }
        )

        SliderSettingCard(
            title = "🔤 字体大小",
            currentValue = "${fontSliderValue.toInt()} sp",
            value = fontSliderValue,
            valueRange = 1f..30f,
            onValueChange = { fontSliderValue = it; prefs.edit().putFloat("font_size", it).apply() },
            startLabel = "1",
            midLabel = "15",
            endLabel = "30"
        )

        SliderSettingCard(
            title = "⬟ 圆角曲率",
            currentValue = "${cornerSliderValue.toInt()} dp",
            value = cornerSliderValue,
            valueRange = 0f..50f,
            onValueChange = { cornerSliderValue = it; prefs.edit().putFloat("corner_radius", it).apply() },
            startLabel = "0",
            midLabel = "25",
            endLabel = "50"
        )

        ColorPickerSection(
            title = "🎨 背景颜色",
            colors = BG_COLORS,
            selectedColor = bgColor,
            onColorSelected = { bgColor = it; prefs.edit().putInt("bg_color", it).apply() }
        )

        ColorPickerSection(
            title = "✏️ 字体颜色",
            colors = TEXT_COLORS,
            selectedColor = textColor,
            onColorSelected = { textColor = it; prefs.edit().putInt("text_color", it).apply() }
        )

        SliderSettingCard(
            title = "👁️ 透明度",
            currentValue = "${(bgAlphaValue * 100).toInt()}%",
            value = bgAlphaValue,
            valueRange = 0.1f..1f,
            onValueChange = { bgAlphaValue = it; prefs.edit().putFloat("bg_alpha", it).apply() },
            startLabel = "10%",
            midLabel = "50%",
            endLabel = "100%"
        )

        UserGuideCard()
        PermissionGuideCard(onOpenOverlaySettings = onOpenOverlaySettings, onOpenBatterySettings = onOpenBatterySettings)

        UpdateCheckCard(
            isChecking = isChecking,
            onCheckUpdate = {
                if (!isChecking) {
                    isChecking = true
                    scope.launch {
                        val info = UpdateChecker.check("1.55")
                        isChecking = false
                        if (info.hasUpdate) {
                            updateVersion = info.latestVersion
                            updateApkUrl = info.apkDownloadUrl
                            showUpdateDialog = true
                        } else {
                            Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )

        Spacer(Modifier.height(32.dp))
    }
}

// ==============================
// 子组件：标题
// ==============================

/** 应用标题 */
@Composable
private fun TitleSection() {
    Text(
        text = "🔋 电池温度悬浮窗",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        textAlign = TextAlign.Center
    )
}

// ==============================
// 子组件：悬浮窗开关
// ==============================

/** 悬浮窗启动/停止卡片 */
@Composable
private fun FloatingWindowCard(isServiceRunning: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
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
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) { Text(if (isServiceRunning) "关闭" else "启动") }
        }
    }
}

// ==============================
// 子组件：保活卡片
// ==============================

/** 进程保活控制卡片 */
@Composable
private fun KeepaliveCard(keepaliveEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("进程保活", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val a11yRunning = KeepliveA11yService.isRunning
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (a11yRunning) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (a11yRunning) "保活中 ✓" else "已关闭",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = keepaliveEnabled, onCheckedChange = onToggle)
        }
    }
}

// ==============================
// 子组件：用户提示
// ==============================

/** 用户必读提示卡片 */
@Composable
private fun UserGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⚠️ 用户必读",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "请前往「手机管家(各品牌名称可能不同,例如i管家) → 权限管理 → 自启动」允许本应用自启动，" +
                        "并在「电池 → 后台耗电管理」中设为「允许后台高耗电」，否则悬浮窗可能被系统清理。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "如果系统权限受限制，请到应用信息中解除，例如iQOO操作步骤：桌面长按应用 → 应用信息 → 点击右上角解除。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

// ==============================
// 子组件：权限引导
// ==============================

/** 权限引导按钮卡片 */
@Composable
private fun PermissionGuideCard(
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
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
}

// ==============================
// 子组件：检查更新
// ==============================

/** 版本更新检查卡片 */
@Composable
private fun UpdateCheckCard(
    isChecking: Boolean,
    onCheckUpdate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("🎯 版本更新", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "v1.55",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onCheckUpdate,
                    enabled = !isChecking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (isChecking) "检查中…" else "检查更新",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}