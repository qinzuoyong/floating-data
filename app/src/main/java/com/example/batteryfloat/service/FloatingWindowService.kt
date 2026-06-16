package com.example.batteryfloat.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.batteryfloat.MainActivity
import com.example.batteryfloat.R
import com.example.batteryfloat.monitor.BatteryMonitor
import com.example.batteryfloat.view.FloatingWindowView

/**
 * 悬浮窗前台服务
 * - 管理 WindowManager 悬浮窗生命周期
 * - 通过 BatteryMonitor 实时更新温度显示
 * - 前台通知常驻，防止被系统清理
 * - START_STICKY 保证被杀后自动重建
 * - AlarmManager 心跳每 5 分钟检查存活性
 * - onTaskRemoved 通过 AlarmManager 延迟重启
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: FloatingWindowView? = null
    private var batteryMonitor: BatteryMonitor? = null
    private var heartbeatPendingIntent: PendingIntent? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("floating_prefs", Context.MODE_PRIVATE)
    }

    /** 保活用 1x1 不可见覆盖层 (TYPE_APPLICATION_OVERLAY) */
    private var aliveView: View? = null

    companion object {
        /** 服务是否正在运行（供外部查询） */
        @Volatile
        var isRunning = false
        private const val TAG = "FloatingWindowService"
        const val CHANNEL_ID = "battery_temp_channel"
        const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L  // 5分钟心跳
        const val PREF_FLOATING_RUNNING = "floating_was_running"

        /** 启动悬浮窗服务 */
        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止悬浮窗服务 */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        isRunning = true
        super.onCreate()
        // 记录悬浮窗运行状态（供开机自启判断）
        prefs.edit().putBoolean(PREF_FLOATING_RUNNING, true).apply()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scheduleHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        showFloatingWindow()
        // 每次启动都添加 1x1 保活覆盖层
        addAliveOverlay()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 屏幕方向切换（横↔竖）时重新钳位悬浮窗位置
     * 防止横屏切竖屏后悬浮窗跑出屏幕外
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "配置变化，重新钳位悬浮窗位置")
        floatingView?.clampToScreenBounds()
    }

    override fun onDestroy() {
        isRunning = false
        // 记录悬浮窗停止状态
        prefs.edit().putBoolean(PREF_FLOATING_RUNNING, false).apply()
        cancelHeartbeat()
        stopMonitoring()
        removeFloatingWindow()
        removeAliveOverlay()
        // 取消注册 SharedPreferences 监听器
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null

        super.onDestroy()
    }

    /**
     * 当用户从最近任务中划掉应用时回调
     * 通过 AlarmManager 延迟 1 秒重启 Service
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: 应用被划掉，延迟重启")
        val restartIntent = Intent(applicationContext, FloatingWindowService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            0,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    // ===== 心跳保活 =====

    /**
     * 设置 AlarmManager 周期性心跳
     * 每 5 分钟触发 onStartCommand，确保 Service 持续存活
     */
    private fun scheduleHeartbeat() {
        val intent = Intent(applicationContext, FloatingWindowService::class.java)
        heartbeatPendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                heartbeatPendingIntent!!
            )
            Log.d(TAG, "心跳已设置: 间隔 ${HEARTBEAT_INTERVAL_MS / 60000} 分钟")
        } catch (e: Exception) {
            Log.e(TAG, "设置心跳失败", e)
        }
    }

    /** 取消心跳 */
    private fun cancelHeartbeat() {
        heartbeatPendingIntent?.let { pi ->
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pi)
        }
        heartbeatPendingIntent = null
    }

    // ===== 悬浮窗管理 =====

    private fun showFloatingWindow() {
        if (floatingView != null) return

        floatingView = FloatingWindowView(this).also { view ->
            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                gravity = Gravity.TOP or Gravity.START
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                x = 100
                y = 400
            }
            view.setLayoutParams(params)
            windowManager.addView(view, params)
        }

        // 注册 SharedPreferences 监听器，实现设置实时生效
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            floatingView?.reloadAppearance()
        }
        prefsListener?.let { prefs.registerOnSharedPreferenceChangeListener(it) }

        startMonitoring()
    }

    private fun removeFloatingWindow() {
        floatingView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "移除悬浮窗失败: ${e.message}")
            }
            floatingView = null
        }
    }

    // ===== 温度监控 =====

    private fun startMonitoring() {
        val view = floatingView ?: return
        batteryMonitor = BatteryMonitor(this, view).also {
            it.start()
        }
    }

    private fun stopMonitoring() {
        batteryMonitor?.stop()
        batteryMonitor = null
    }

    // ===== 1x1 保活覆盖层（极低功耗，参考 GKD 方案） =====

    /**
     * 添加 1x1 不可见的 TYPE_APPLICATION_OVERLAY 覆盖层
     *
     * 原理（参考 GKD 的保活机制）：
     * 在 WindowManager 中添加一个极小不可触摸的覆盖层窗口，
     * 让系统认为该进程正在提供重要的 UI 覆盖服务，
     * 从而提高 OOM 杀进程时的优先级，降低被回收概率。
     * 窗口仅 1x1 像素且不可触摸，CPU/GPU 消耗接近于零。
     *
     * 使用已授权的 SYSTEM_ALERT_WINDOW 权限和 TYPE_APPLICATION_OVERLAY 类型，
     * 无需额外权限。
     */
    private fun addAliveOverlay() {
        if (aliveView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            width = 1
            height = 1
            x = 0
            y = 0
        }
        try {
            wm.addView(view, lp)
            aliveView = view
            Log.d(TAG, "1x1 保活覆盖层添加成功")
        } catch (e: Exception) {
            Log.w(TAG, "添加保活覆盖层失败: ${e.message}")
        }
    }

    /** 移除 1x1 保活覆盖层 */
    private fun removeAliveOverlay() {
        aliveView?.let { view ->
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
                Log.d(TAG, "保活覆盖层已移除")
            } catch (e: Exception) {
                Log.w(TAG, "移除保活覆盖层失败: ${e.message}")
            }
            aliveView = null
        }
    }

    // ===== 通知管理 =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "电池温度悬浮窗监控服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("电池温度监控")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
}