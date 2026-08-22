package com.example.batteryfloat.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.batteryfloat.PrefsKeys

/**
 * 无障碍保活服务（等价移植 GKD 保活机制）
 *
 * 核心原理：用户在系统设置开启本服务后，system_server 持有
 * BIND_ACCESSIBILITY_SERVICE 绑定，进程优先级提升至「可感知」级别：
 * - 进程崩溃/被杀后系统自动重新绑定，秒级自愈（无闹钟 15 分钟空窗）
 * - 开机解锁后系统直接绑定本服务拉起进程，不依赖 BOOT_COMPLETED，
 *   不受厂商「自启动管理」拦截（GKD 的 manifest 即无任何开机广播）
 *
 * onServiceConnected 时：
 * 1. 挂 1x1 TYPE_ACCESSIBILITY_OVERLAY 保活窗，进一步钉死进程优先级
 *    （该窗口类型仅无障碍服务可添加，不依赖悬浮窗权限）
 * 2. 按与 BootReceiver 完全一致的门控拉起悬浮窗服务（免广播开机自启通道）
 *
 * 纯保活用途：不读取屏幕内容（canRetrieveWindowContent=false）、不处理事件
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    /** 1x1 保活覆盖层（TYPE_ACCESSIBILITY_OVERLAY） */
    private var aliveView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        Log.i(TAG, "无障碍保活已连接")
        addAliveOverlay()
        tryRestoreFloatingWindow()
        // 无障碍在位 → 停用周期兜底（15 分钟心跳闹钟 + 看门狗 Job），零周期唤醒省电
        FloatingWindowService.upgradeKeepAlive(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 纯保活，不处理任何无障碍事件
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        isRunning = false
        removeAliveOverlay()
        Log.i(TAG, "无障碍保活已断开")
        Toast.makeText(applicationContext, "无障碍保活已关闭", Toast.LENGTH_SHORT).show()
        // 无障碍退位 → 周期兜底层无缝顶上：重排看门狗，并借 onStartCommand 既有路径
        // 重排心跳 + 补挂 1px 应用层 overlay（接替刚移除的无障碍 overlay）
        if (FloatingWindowService.isRunning) {
            KeepAliveJobService.schedule(applicationContext)
            try {
                startService(Intent(applicationContext, FloatingWindowService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "恢复周期保活失败: ${e.message}")
            }
        }
        super.onDestroy()
    }

    /**
     * 恢复悬浮窗（免广播开机自启通道）
     * 门控与 BootReceiver 完全一致，避免用户已关闭悬浮窗时出现「幽灵服务」：
     * BOOT_AUTO_START 开 + FLOATING_WAS_RUNNING + 已授悬浮窗权限
     */
    private fun tryRestoreFloatingWindow() {
        if (FloatingWindowService.isRunning) return
        val prefs: SharedPreferences =
            getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PrefsKeys.BOOT_AUTO_START, true)) return
        if (!prefs.getBoolean(PrefsKeys.FLOATING_WAS_RUNNING, false)) return
        if (!Settings.canDrawOverlays(this)) return
        // 本进程正被系统绑定，不受后台 startForegroundService 限制
        // （同 GKD StatusService.autoStart 的启动条件）
        FloatingWindowService.start(this)
        Log.i(TAG, "无障碍通道恢复悬浮窗服务")
    }

    // ===== 1x1 保活覆盖层 =====

    private fun addAliveOverlay() {
        removeAliveOverlay()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.START or Gravity.TOP
            width = 1
            height = 1
            x = 0
            y = 0
            packageName = this@KeepAliveAccessibilityService.packageName
        }
        try {
            wm.addView(view, lp)
            aliveView = view
            Log.d(TAG, "无障碍保活覆盖层添加成功")
        } catch (e: Exception) {
            // 个别设备抛 BadTokenException；保活核心是系统绑定，此层失败仅记录
            aliveView = null
            Log.w(TAG, "添加无障碍保活覆盖层失败: ${e.message}")
        }
    }

    private fun removeAliveOverlay() {
        aliveView?.let { view ->
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "移除无障碍保活覆盖层失败: ${e.message}")
            }
            aliveView = null
        }
    }

    companion object {
        private const val TAG = "KeepAliveA11y"

        /** 服务是否正在运行（供 UI 与 FloatingWindowService 查询） */
        @Volatile
        var isRunning = false
            private set

        /** 当前服务实例（应用内关闭开关需调用 disableSelf） */
        @Volatile
        var instance: KeepAliveAccessibilityService? = null
            private set

        /** 本服务是否已在系统无障碍设置中启用（UI 显示真实状态用） */
        fun isEnabledInSettings(context: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (enabled.isNullOrBlank()) return false
                val cn = ComponentName(context, KeepAliveAccessibilityService::class.java)
                val full = cn.flattenToString()
                val short = cn.flattenToShortString()
                enabled.split(':').any {
                    it.equals(full, true) || it.equals(short, true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "查询无障碍启用状态失败", e)
                false
            }
        }
    }
}
