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

    companion object {
        /** 服务是否正在运行（供外部查询） */
        @Volatile
        var isRunning = false
        private const val TAG = "FloatingWindowService"
        const val CHANNEL_ID = "battery_temp_channel"
        const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L  // 5分钟心跳

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
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scheduleHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        showFloatingWindow()

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
        cancelHeartbeat()
        stopMonitoring()
        removeFloatingWindow()
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