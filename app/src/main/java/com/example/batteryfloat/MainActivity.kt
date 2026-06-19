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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.example.batteryfloat.service.FloatingWindowService
import com.example.batteryfloat.ui.AppNavigation
import com.example.batteryfloat.ui.theme.BatteryFloatingTheme
import com.example.batteryfloat.update.ApkDownloader
import com.example.batteryfloat.update.DownloadState
import com.example.batteryfloat.update.UpdateChecker
import com.example.batteryfloat.update.UpdateDownloadDialog
import com.example.batteryfloat.WebViewActivity
import kotlinx.coroutines.launch

/**
 * 主 Activity
 * 入口：底部导航（首页 / 外观 / 关于），多页面架构
 */
class MainActivity : ComponentActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("floating_prefs", Context.MODE_PRIVATE)
    }

    @Volatile
    private var isLaunchingExternal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BatteryFloatingTheme {
                var showUpdateDialog by remember { mutableStateOf(false) }
                var updateVersion by remember { mutableStateOf("") }
                var updateApkUrl by remember { mutableStateOf("") }
                val downloadState by ApkDownloader.downloadState.collectAsState()

                // 启动时自动检查更新
                LaunchedEffect(Unit) {
                    val info = UpdateChecker.check(BuildConfig.VERSION_NAME)
                    if (info.hasUpdate) {
                        updateVersion = info.latestVersion
                        updateApkUrl = info.apkDownloadUrl
                        showUpdateDialog = true
                    }
                }

                AppNavigation(
                    prefs = prefs,
                    onStartService = { FloatingWindowService.start(this) },
                    onStopService = { FloatingWindowService.stop(this) },
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onOpenBatterySettings = { openBatteryOptimizationSettings() },
                    onOpenExternalLink = { url, title ->
                        isLaunchingExternal = true
                        try {
                            startActivity(Intent(this, WebViewActivity::class.java).apply {
                                putExtra(WebViewActivity.EXTRA_URL, url)
                                putExtra(WebViewActivity.EXTRA_TITLE, title)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(this, "无法打开页面", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onInstallApk = { file ->
                        isLaunchingExternal = true
                        ApkDownloader.install(this, file)
                    }
                )

                // 更新弹窗（全局，任意页面可见）
                if (showUpdateDialog) {
                    UpdateDownloadDialog(
                        updateVersion, downloadState,
                        onStartDownload = {
                            if (updateApkUrl.isNotBlank()) lifecycleScope.launch { ApkDownloader.download(this@MainActivity, updateApkUrl) }
                        },
                        onInstall = {
                            (downloadState as? DownloadState.Completed)?.let { ApkDownloader.install(this@MainActivity, it.file) }
                        },
                        onDismiss = {
                            showUpdateDialog = false
                            if (downloadState is DownloadState.Completed) ApkDownloader.cleanup(this@MainActivity)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isLaunchingExternal = false
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
        if (isLaunchingExternal) {
            Log.d("MainActivity", "onUserLeaveHint: 启动外部 Intent，跳过 finishAndRemoveTask")
            return
        }
        if (prefs.getBoolean("hide_recents", true)) {
            Log.i("MainActivity", "隐藏后台(Home键): finishAndRemoveTask")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && prefs.getBoolean("hide_recents", true)) {
            Log.i("MainActivity", "隐藏后台(返回键): finishAndRemoveTask")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}