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
import androidx.lifecycle.lifecycleScope
import com.example.batteryfloat.service.FloatingWindowService
import com.example.batteryfloat.ui.AppNavigation
import com.example.batteryfloat.ui.theme.BatteryFloatingTheme
import com.example.batteryfloat.update.UpdateChecker
import com.example.batteryfloat.update.ApkDownloader
import com.example.batteryfloat.util.KeepaliveManager
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

        // 后台检查版本更新（使用 lifecycleScope，随生命周期自动取消）
        lifecycleScope.launch {
            val info = UpdateChecker.check("1.59")
            if (info.hasUpdate) {
                // 更新对话框由用户手动触发，后台仅记录
                prefs.edit().putString("latest_version", info.latestVersion).apply()
                prefs.edit().putString("apk_url", info.apkDownloadUrl).apply()
            }
        }

        setContent {
            BatteryFloatingTheme {
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isLaunchingExternal = false
        // 重置保活管理器的外部标志
        KeepaliveManager.isOpeningExternal = false
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
        if (isLaunchingExternal || KeepaliveManager.isOpeningExternal) {
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