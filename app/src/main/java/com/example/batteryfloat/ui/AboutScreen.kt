package com.example.batteryfloat.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.service.KeepliveA11yService
import com.example.batteryfloat.update.ApkDownloader
import com.example.batteryfloat.update.DownloadState
import com.example.batteryfloat.update.UpdateChecker
import com.example.batteryfloat.update.UpdateDownloadDialog
import com.example.batteryfloat.util.KeepaliveManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * 关于页面 - 帮助 / 权限 / 保活 / 更新 / 关于
 * GitHub/Gitee 链接使用 Intent.ACTION_VIEW 跳转浏览器（修复闪退）
 */
@Composable
fun AboutScreen(
    prefs: SharedPreferences,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onInstallApk: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var keepaliveEnabled by remember { mutableStateOf(prefs.getBoolean("keepalive_enabled", false)) }
    var bootAutoStart by remember { mutableStateOf(prefs.getBoolean("boot_auto_start", true)) }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateVersion by remember { mutableStateOf("") }
    var updateApkUrl by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    val downloadState by ApkDownloader.downloadState.collectAsState()

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("关于", fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

        UserGuideCard()
        PermissionGuideCard(onOpenOverlaySettings, onOpenBatterySettings)
        KeepaliveSection(keepaliveEnabled, bootAutoStart,
            onKeepaliveToggle = { enabled ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                keepaliveEnabled = enabled
                scope.launch {
                    val success = KeepaliveManager.toggleKeepalive(context, enabled)
                    if (!success) keepaliveEnabled = !enabled
                    else prefs.edit().putBoolean("keepalive_enabled", enabled).apply()
                }
            },
            onBootToggle = {
                bootAutoStart = it
                prefs.edit().putBoolean("boot_auto_start", it).apply()
            }
        )
        UpdateCheckCard(isChecking) {
            if (!isChecking) {
                isChecking = true
                scope.launch {
                    val info = UpdateChecker.check("1.57")
                    isChecking = false
                    if (info.hasUpdate) {
                        updateVersion = info.latestVersion
                        updateApkUrl = info.apkDownloadUrl
                        showUpdateDialog = true
                    } else Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }
        AboutInfoCard()
        Spacer(Modifier.height(32.dp))
    }

    if (showUpdateDialog) {
        UpdateDownloadDialog(
            updateVersion, downloadState,
            onStartDownload = {
                if (updateApkUrl.isNotBlank()) scope.launch { ApkDownloader.download(context, updateApkUrl) }
            },
            onInstall = { (downloadState as? DownloadState.Completed)?.let { onInstallApk(it.file) } },
            onDismiss = {
                showUpdateDialog = false
                if (downloadState is DownloadState.Completed) ApkDownloader.cleanup(context)
            }
        )
    }
}

// ==============================
// 子组件
// ==============================

@Composable
private fun UserGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("用户必读", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.height(6.dp))
            Text("请前往「手机管家 → 权限管理 → 自启动」允许本应用自启动，" +
                    "并在「电池 → 后台耗电管理」中设为「允许后台高耗电」。",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(6.dp))
            Text("如果系统权限受限，请在应用信息中解除限制。",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun PermissionGuideCard(onOpenOverlaySettings: () -> Unit, onOpenBatterySettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("权限引导", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenOverlaySettings, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("开启悬浮窗权限") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("忽略电池优化") }
        }
    }
}

@Composable
private fun KeepaliveSection(
    keepaliveEnabled: Boolean, bootAutoStart: Boolean,
    onKeepaliveToggle: (Boolean) -> Unit, onBootToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("保活与自启", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("进程保活", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val a11yRunning = KeepliveA11yService.isRunning
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (a11yRunning) Color(0xFF4CAF50) else Color(0xFFBDBDBD)))
                        Spacer(Modifier.width(4.dp))
                        Text(if (a11yRunning) "保活中 ✓" else "已关闭", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = keepaliveEnabled, onCheckedChange = onKeepaliveToggle)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("开机自启动", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(if (bootAutoStart) "开机后智能判断悬浮窗状态" else "已关闭", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = bootAutoStart, onCheckedChange = onBootToggle)
            }
        }
    }
}

@Composable
private fun UpdateCheckCard(isChecking: Boolean, onCheckUpdate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("版本更新", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("v1.57", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = onCheckUpdate, enabled = !isChecking, shape = RoundedCornerShape(12.dp)) {
                Text(if (isChecking) "检查中…" else "检查更新", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 关于信息卡片 - 含修复的 GitHub/Gitee 链接 */
@Composable
private fun AboutInfoCard() {
    val context = LocalContext.current
    val safeOpenUrl: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            Text("神奇悬浮窗", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("v1.57", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("实时监测电池温度与功耗的 Android 悬浮窗工具", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(modifier = Modifier.fillMaxWidth(0.6f))
            Spacer(Modifier.height(12.dp))
            Text("作者: qinzuoyong", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            // GitHub 链接（修复：点击跳转浏览器）
            Text("GitHub", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    safeOpenUrl("https://github.com/qinzuoyong/floating-data")
                })
            Spacer(Modifier.height(6.dp))
            // Gitee 链接（修复：点击跳转浏览器）
            Text("Gitee", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    safeOpenUrl("https://gitee.com/qinzuoyong/floating-data")
                })
        }
    }
}