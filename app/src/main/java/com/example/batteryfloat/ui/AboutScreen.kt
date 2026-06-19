package com.example.batteryfloat.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.batteryfloat.BuildConfig
import com.example.batteryfloat.update.ApkDownloader
import com.example.batteryfloat.update.DownloadState
import com.example.batteryfloat.update.UpdateChecker
import com.example.batteryfloat.update.UpdateDownloadDialog
import kotlinx.coroutines.launch
import java.io.File

/**
 * 关于页面 - 权限引导 / 开机自启 / 更新 / 关于
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

        PermissionGuideCard(onOpenOverlaySettings, onOpenBatterySettings)
        BootSection(bootAutoStart) {
            bootAutoStart = it
            prefs.edit().putBoolean("boot_auto_start", it).apply()
        }
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
                    } else Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }
        AboutInfoCard(onOpenExternalLink)
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
private fun BootSection(bootAutoStart: Boolean, onBootToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("开机自启", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
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
                    Text("v1.59", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = onCheckUpdate, enabled = !isChecking, shape = RoundedCornerShape(12.dp)) {
                Text(if (isChecking) "检查中…" else "检查更新", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 关于信息卡片 - 含 WebView 内置浏览的 GitHub/Gitee 链接 */
@Composable
private fun AboutInfoCard(onOpenExternalLink: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val openInWebView: (String, String) -> Unit = { url, title ->
        onOpenExternalLink(url, title)
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
            Text("v1.59", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    openInWebView("https://github.com/qinzuoyong/floating-data", "GitHub")
                })
            Spacer(Modifier.height(6.dp))
            // Gitee 链接（WebView 内置浏览）
            Text("Gitee", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    openInWebView("https://gitee.com/qinzuoyong/floating-data", "Gitee")
                })
        }
    }
}