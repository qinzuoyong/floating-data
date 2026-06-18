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
import com.example.batteryfloat.service.FloatingWindowService
import com.example.batteryfloat.ui.AppNavigation
import com.example.batteryfloat.ui.PermissionGuideDialog
import com.example.batteryfloat.ui.theme.BatteryFloatingTheme
import com.example.batteryfloat.update.UpdateChecker
import com.example.batteryfloat.update.ApkDownloader
import com.example.batteryfloat.util.KeepaliveManager
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

        // 后台检查版本更新
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            val info = UpdateChecker.check("1.58")
            if (info.hasUpdate) {
                // 更新对话框由用户手动触发，后台仅记录
                prefs.edit().putString("latest_version", info.latestVersion).apply()
                prefs.edit().putString("apk_url", info.apkDownloadUrl).apply()
            }
        }

        setContent {
            // 首次启动弹出权限引导对话框
            val showPermissionGuide = prefs.getBoolean("permission_guide_shown", false)

            BatteryFloatingTheme {
                if (!showPermissionGuide) {
                    PermissionGuideDialog(
                        onDismiss = {
                            prefs.edit().putBoolean("permission_guide_shown", true).apply()
                        },
                        onOpenOverlaySettings = { openOverlaySettings() },
                        onOpenBatterySettings = { openBatteryOptimizationSettings() }
                    )
                }
                AppNavigation(
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
        isLaunchingExternal = false
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