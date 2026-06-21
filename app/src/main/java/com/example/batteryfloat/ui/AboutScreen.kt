package com.example.batteryfloat.ui

import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.BuildConfig
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.update.ApkDownloader
import com.example.batteryfloat.update.DownloadState
import com.example.batteryfloat.update.UpdateChecker
import com.example.batteryfloat.update.UpdateDownloadDialog
import com.example.batteryfloat.ui.theme.DesignSystem
import kotlinx.coroutines.launch
import java.io.File

/**
 * 关于页面 - 权限引导 / 开机自启 / 更新 / 关于
 * 
 * 重新设计要点：
 * 1. 统一间距：使用 DesignSystem 中的 8dp 网格系统
 * 2. 层级分明：权限 > 自启 > 更新 > 关于
 * 3. 美学优化：图标+文字组合、分组标题、卡片圆角
 */
@Composable
fun AboutScreen(
    prefs: SharedPreferences,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenExternalLink: (String, String) -> Unit = { _, _ -> },
    onInstallApk: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bootAutoStart by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.BOOT_AUTO_START, true)) }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateVersion by remember { mutableStateOf("") }
    var updateApkUrl by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    val downloadState by ApkDownloader.downloadState.collectAsState()

    // Android 14+ 受限设置引导对话框
    var showRestrictedGuide by remember { mutableStateOf(false) }

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
            title = "关于",
            modifier = Modifier.padding(top = DesignSystem.SpacingL)
        )

        // ===== 权限引导区 =====
        SectionTitle(title = "权限设置")
        PermissionGuideCard(
            onOpenOverlaySettings = onOpenOverlaySettings,
            onOpenBatterySettings = onOpenBatterySettings,
            onOpenRestrictedGuide = { showRestrictedGuide = true }
        )

        // ===== 开机自启区 =====
        SectionTitle(title = "启动设置")
        BootSection(bootAutoStart) {
            bootAutoStart = it
            prefs.edit().putBoolean(PrefsKeys.BOOT_AUTO_START, it).apply()
        }

        // ===== 版本更新区 =====
        SectionTitle(title = "版本信息")
        UpdateCheckCard(isChecking) {
            if (!isChecking) {
                isChecking = true
                scope.launch {
                    val info = UpdateChecker.check(BuildConfig.VERSION_NAME)
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

        // ===== 关于信息区 =====
        AboutInfoCard(onOpenExternalLink)

        // 底部间距
        Spacer(Modifier.height(DesignSystem.SpacingXl))
    }

    // Android 14+ 受限设置引导对话框
    if (showRestrictedGuide) {
        RestrictedSettingsDialog(
            onDismiss = { showRestrictedGuide = false },
            onOpenAppInfo = {
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(this)
                }
            }
        )
    }

    // 更新下载对话框
    if (showUpdateDialog) {
        UpdateDownloadDialog(
            updateVersion, downloadState,
            onStartDownload = {
                if (updateApkUrl.isNotBlank()) {
                    scope.launch { ApkDownloader.download(context, updateApkUrl) }
                }
            },
            onInstall = { 
                (downloadState as? DownloadState.Completed)?.let { onInstallApk(it.file) } 
            },
            onDismiss = {
                showUpdateDialog = false
                if (downloadState is DownloadState.Completed) {
                    ApkDownloader.cleanup(context)
                }
            }
        )
    }
}

// ==============================
// 子组件
// ==============================

/**
 * 权限引导卡片
 * 
 * 设计特点：
 * 1. 图标带绿色圆形背景（安全/权限）
 * 2. 分层按钮：主要/次要操作
 * 3. 统一的圆角和间距
 */
@Composable
private fun PermissionGuideCard(
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenRestrictedGuide: () -> Unit
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标带绿色圆形背景
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F5E9)),  // 浅绿色背景
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.VerifiedUser, 
                        contentDescription = null, 
                        tint = Color(0xFF2E7D32),  // 绿色图标
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(DesignSystem.SpacingM))
                Text(
                    "权限引导", 
                    fontWeight = FontWeight.SemiBold, 
                    fontSize = DesignSystem.FontSizeBody
                )
            }
            Spacer(Modifier.height(DesignSystem.SpacingM))

            // 悬浮窗权限（主要操作）
            OutlinedButton(
                onClick = onOpenOverlaySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DesignSystem.CornerM)
            ) {
                Text("开启悬浮窗权限")
            }

            Spacer(Modifier.height(DesignSystem.SpacingS))

            // 忽略电池优化
            OutlinedButton(
                onClick = onOpenBatterySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DesignSystem.CornerM)
            ) {
                Text("忽略电池优化")
            }

            // Android 14+ 侧载应用受限设置引导
            if (Build.VERSION.SDK_INT >= 34) {
                Spacer(Modifier.height(DesignSystem.SpacingS))
                FilledTonalButton(
                    onClick = onOpenRestrictedGuide,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(DesignSystem.CornerM),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        Icons.Filled.Security,
                        contentDescription = null,
                        modifier = Modifier.size(DesignSystem.FontSizeHeading.value.dp)
                    )
                    Spacer(Modifier.width(DesignSystem.SpacingS))
                    Text(
                        "解除权限限制（Android 14+）",
                        fontWeight = FontWeight.Medium,
                        fontSize = DesignSystem.FontSizeCaption
                    )
                }
            }
        }
    }
}

/**
 * 开机自启设置卡片
 * 
 * 设计特点：
 * 1. 图标带蓝色圆形背景（启动/系统）
 * 2. 卡片使用不透明的 surfaceContainerLow 背景
 * 3. 统一的间距和圆角
 */
@Composable
private fun BootSection(bootAutoStart: Boolean, onBootToggle: (Boolean) -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标带蓝色圆形背景
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE3F2FD)),  // 浅蓝色背景
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Security, 
                        contentDescription = null, 
                        tint = Color(0xFF1565C0),  // 蓝色图标
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(DesignSystem.SpacingM))
                Text(
                    "开机自启", 
                    fontWeight = FontWeight.SemiBold, 
                    fontSize = DesignSystem.FontSizeBody
                )
            }
            Spacer(Modifier.height(DesignSystem.SpacingM))
            Row(
                Modifier.fillMaxWidth(), 
                verticalAlignment = Alignment.CenterVertically, 
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "开机自启动", 
                        fontWeight = FontWeight.Medium, 
                        fontSize = DesignSystem.FontSizeBody
                    )
                    Text(
                        if (bootAutoStart) "开机后智能判断悬浮窗状态" else "已关闭", 
                        fontSize = DesignSystem.FontSizeCaption, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = bootAutoStart, 
                    onCheckedChange = onBootToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

/**
 * 版本更新检查卡片
 * 
 * 设计特点：
 * 1. 图标带橙色圆形背景（更新/下载）
 * 2. 卡片使用不透明的 surfaceContainerLow 背景
 * 3. 统一的间距和圆角
 */
@Composable
private fun UpdateCheckCard(isChecking: Boolean, onCheckUpdate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(DesignSystem.CardPadding), 
            verticalAlignment = Alignment.CenterVertically, 
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标带橙色圆形背景
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFF3E0)),  // 浅橙色背景
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.SystemUpdateAlt, 
                        contentDescription = null, 
                        tint = Color(0xFFE65100),  // 橙色图标
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(DesignSystem.SpacingM))
                Column {
                    Text(
                        "版本更新", 
                        fontWeight = FontWeight.SemiBold, 
                        fontSize = DesignSystem.FontSizeBody
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME}", 
                        fontSize = DesignSystem.FontSizeCaption, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FilledTonalButton(
                onClick = onCheckUpdate, 
                enabled = !isChecking, 
                shape = RoundedCornerShape(DesignSystem.CornerM)
            ) {
                Text(
                    if (isChecking) "检查中…" else "检查更新", 
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 关于信息卡片 - 含 WebView 内置浏览的 GitHub/Gitee 链接
 * 
 * 设计特点：
 * 1. 图标带紫色圆形背景（信息/关于）
 * 2. 居中布局
 * 3. 分割线分隔
 * 4. 链接样式统一
 */
@Composable
private fun AboutInfoCard(onOpenExternalLink: (String, String) -> Unit = { _, _ -> }) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.CornerL),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.ElevationNone),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(DesignSystem.CardPaddingLarge), 
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 应用图标 - 带紫色圆形背景
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF3E5F5)),  // 浅紫色背景
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Info, 
                    contentDescription = null, 
                    modifier = Modifier.size(28.dp), 
                    tint = Color(0xFF6A1B9A)  // 紫色图标
                )
            }
            
            Spacer(Modifier.height(DesignSystem.SpacingM))
            
            // 应用名称
            Text(
                "神奇悬浮窗", 
                fontWeight = FontWeight.Bold, 
                fontSize = DesignSystem.FontSizeHeading, 
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // 版本号
            Text(
                "v${BuildConfig.VERSION_NAME}", 
                fontSize = DesignSystem.FontSizeCaption, 
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(Modifier.height(DesignSystem.SpacingS))
            
            // 应用描述
            Text(
                "实时监测电池温度与功耗的 Android 悬浮窗工具", 
                fontSize = DesignSystem.FontSizeCaption, 
                color = MaterialTheme.colorScheme.onSurfaceVariant, 
                textAlign = TextAlign.Center
            )
            
            Spacer(Modifier.height(DesignSystem.SpacingM))
            
            // 分割线
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(0.6f),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            
            Spacer(Modifier.height(DesignSystem.SpacingM))
            
            // 作者信息
            Text(
                "作者: qinzuoyong", 
                fontSize = DesignSystem.FontSizeCaption, 
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(Modifier.height(DesignSystem.SpacingS))
            
            // GitHub 链接
            Text(
                "GitHub", 
                fontSize = DesignSystem.FontSizeBody, 
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, 
                    indication = null
                ) {
                    onOpenExternalLink("https://github.com/qinzuoyong/floating-data", "GitHub")
                }
            )
            
            Spacer(Modifier.height(DesignSystem.SpacingS))
            
            // Gitee 链接
            Text(
                "Gitee", 
                fontSize = DesignSystem.FontSizeBody, 
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, 
                    indication = null
                ) {
                    onOpenExternalLink("https://gitee.com/qinzuoyong/floating-data", "Gitee")
                }
            )
        }
    }
}
