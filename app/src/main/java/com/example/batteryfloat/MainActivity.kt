package com.example.batteryfloat

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.example.batteryfloat.service.FloatingWindowService
import com.example.batteryfloat.ui.AppNavigation
import com.example.batteryfloat.ui.RestrictedSettingsDialog
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
        getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var isLaunchingExternal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // 读取主题模式设置
            var themeMode by remember { mutableIntStateOf(prefs.getInt(PrefsKeys.THEME_MODE, 0)) }

            // 监听主题设置变化
            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == PrefsKeys.THEME_MODE) {
                        themeMode = prefs.getInt(PrefsKeys.THEME_MODE, 0)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            BatteryFloatingTheme(themeMode = themeMode) {
                var showUpdateDialog by remember { mutableStateOf(false) }
                var updateVersion by remember { mutableStateOf("") }
                var updateApkUrl by remember { mutableStateOf("") }
                val downloadState by ApkDownloader.downloadState.collectAsState()

                // Android 14+ 受限设置引导对话框状态
                var showRestrictedGuide by remember { mutableStateOf(false) }

                // 启动时自动检查更新
                LaunchedEffect(Unit) {
                    try {
                        val info = UpdateChecker.check(BuildConfig.VERSION_NAME)
                        if (info.hasUpdate) {
                            updateVersion = info.latestVersion
                            updateApkUrl = info.apkDownloadUrl
                            showUpdateDialog = true
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "检查更新失败", e)
                    }
                }

                AppNavigation(
                    prefs = prefs,
                    onStartService = { FloatingWindowService.start(this) },
                    onStopService = { FloatingWindowService.stop(this) },
                    onOpenOverlaySettings = {
                        openOverlaySettingsWithGuide { showRestrictedGuide = true }
                    },
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

                // Android 14+ 受限设置引导对话框
                if (showRestrictedGuide) {
                    RestrictedSettingsDialog(
                        onDismiss = { showRestrictedGuide = false },
                        onOpenAppInfo = {
                            isLaunchingExternal = true
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:$packageName")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    )
                }

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

    /**
     * 智能打开悬浮窗权限设置页
     *
     * Android 14+ 对侧载应用的 SYSTEM_ALERT_WINDOW 权限实行「受限设置」管控，
     * 直接打开 ACTION_MANAGE_OVERLAY_PERMISSION 页面时开关为灰色不可操作。
     * 本方法拦截此场景，首次弹出引导对话框，引导用户先解除受限设置。
     *
     * 引导过的用户再次点击时直接打开权限开关页（受限设置已允许，开关可操作）。
     */
    private fun openOverlaySettingsWithGuide(showGuide: () -> Unit) {
        isLaunchingExternal = true

        // Android 14+ 且尚未授予悬浮窗权限 → 检查是否需要引导
        if (Build.VERSION.SDK_INT >= 34 && !Settings.canDrawOverlays(this)) {
            val guideShown = prefs.getBoolean(PrefsKeys.RESTRICTED_SETTINGS_GUIDED, false)
            if (!guideShown) {
                prefs.edit().putBoolean(PrefsKeys.RESTRICTED_SETTINGS_GUIDED, true).apply()
                showGuide()
                return
            }
        }

        // 正常打开悬浮窗权限设置页
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Log.w("MainActivity", "打开悬浮窗权限设置失败", e)
            android.widget.Toast.makeText(this, "无法打开权限设置", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isLaunchingExternal = true
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                Log.w("MainActivity", "打开电池优化设置失败", e)
                android.widget.Toast.makeText(this, "无法打开电池优化设置", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isLaunchingExternal) {
            Log.d("MainActivity", "onUserLeaveHint: 启动外部 Intent，跳过 finishAndRemoveTask")
            return
        }
        if (prefs.getBoolean(PrefsKeys.HIDE_RECENTS, true)) {
            Log.i("MainActivity", "隐藏后台(Home键): finishAndRemoveTask")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && prefs.getBoolean(PrefsKeys.HIDE_RECENTS, true)) {
            Log.i("MainActivity", "隐藏后台(返回键): finishAndRemoveTask")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
