package com.example.batteryfloat.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.data.PrivBatteryProvider
import com.example.batteryfloat.monitor.BatteryMonitor
import com.example.batteryfloat.notif.Notifs
import com.example.batteryfloat.view.FloatingWindowView

/**
 * 悬浮窗前台服务
 * - 管理 WindowManager 悬浮窗生命周期
 * - 通过 BatteryMonitor 实时更新温度显示
 * - 前台通知常驻，防止被系统清理
 * - START_REDELIVER_INTENT 保证被杀后自动重建并重传 Intent
 * - AlarmManager 精确心跳每 15 分钟检查存活性（突破 Doze 限制）
 * - onTaskRemoved 通过 AlarmManager 延迟重启（兼容 Android 12+）
 *
 * v1.65 优化：
 * - 心跳从 setRepeating 升级为 setExactAndAllowWhileIdle，突破 Doze 限制
 * - onTaskRemoved 增加异常捕获，兼容 Android 12+ 前台服务启动限制
 * - 1x1 保活覆盖层增加失败延迟重试机制
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: FloatingWindowView? = null
    private var batteryMonitor: BatteryMonitor? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 主线程 Handler，用于延迟重试 overlay 添加 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 保活用 1x1 不可见覆盖层 (TYPE_APPLICATION_OVERLAY) */
    private var aliveView: View? = null

    /** 跟踪上次屏幕方向，用于 onConfigurationChanged 时判断旧方向 */
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    companion object {
        /** 服务是否正在运行（供外部查询） */
        @Volatile
        var isRunning = false
        private const val TAG = "FloatingWindowService"
        /** 通知渠道/ID 集中管理,见 Notifs(此处保留别名供既有引用使用) */
        const val CHANNEL_ID = Notifs.CHANNEL_BATTERY
        const val NOTIFICATION_ID = Notifs.ID_FLOATING
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L  // 15分钟心跳
        /** 无障碍自愈巡检间隔(兜底触发;即时场景由无障碍 onDestroy 钩子负责) */
        private const val A11Y_PATROL_INTERVAL_MS = 5 * 60 * 1000L
        const val PREF_FLOATING_RUNNING = PrefsKeys.FLOATING_WAS_RUNNING

        /** 外观/功能相关 key 集合，这些 key 变化时刷新悬浮窗外观和缓存 */
        private val APPEARANCE_KEYS = setOf(
            PrefsKeys.FONT_SIZE,
            PrefsKeys.CORNER_RADIUS,
            PrefsKeys.BG_ALPHA,
            PrefsKeys.BG_COLOR,
            PrefsKeys.TEXT_COLOR,
            PrefsKeys.SHOW_POWER,
            PrefsKeys.LOCK_DRAG_ENABLED,  // 锁定开关变化时需更新 lockEnabled 缓存
            PrefsKeys.LOCK_DRAG_ENGAGED   // 锁定状态变化时需更新 lockEngaged 缓存
        )

        /** 启动悬浮窗服务 */
        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.startForegroundService(intent)
        }

        /** 停止悬浮窗服务 */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.stopService(intent)
        }

        /** 心跳 PendingIntent(requestCode 1；schedule/cancel/upgradeKeepAlive 共用同一构造) */
        private fun heartbeatPendingIntent(context: Context): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                1,
                Intent(context, FloatingWindowService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun cancelHeartbeatAlarm(context: Context) {
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .cancel(heartbeatPendingIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "取消心跳闹钟失败: ${e.message}")
            }
        }

        /**
         * 无障碍保活在位时调用：取消已排的周期兜底（心跳闹钟 + 看门狗 Job）
         * 进程由系统绑定保活（崩溃秒级重绑），周期唤醒只浪费电
         */
        fun upgradeKeepAlive(context: Context) {
            cancelHeartbeatAlarm(context)
            KeepAliveJobService.cancel(context)
        }
    }

    /** 无障碍自愈巡检:服务存续期间低频检查,覆盖 onDestroy 钩子没跑到的场景 */
    private val a11yPatrolRunnable = object : Runnable {
        override fun run() {
            A11ySelfHealer.maybeHeal(this@FloatingWindowService, trigger = "patrol")
            mainHandler.postDelayed(this, A11Y_PATROL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 记录悬浮窗运行状态（供开机自启判断）
        prefs.edit().putBoolean(PREF_FLOATING_RUNNING, true).apply()
        Notifs.ensureChannels(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scheduleHeartbeat()
        KeepAliveJobService.schedule(this)
        mainHandler.postDelayed(a11yPatrolRunnable, A11Y_PATROL_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        showFloatingWindow()
        // 每次启动都添加 1x1 保活覆盖层
        addAliveOverlay()
        // 重新调度下次心跳（setExactAndAllowWhileIdle 是一次性的，需每次触发后重新设置）
        scheduleHeartbeat()

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 屏幕方向切换（横↔竖）时重新钳位悬浮窗位置
     * 防止横屏切竖屏后悬浮窗跑出屏幕外
     *
     * 关键修复：onConfigurationChanged 触发时 currentWindowMetrics 已更新为新方向，
     * saveCurrentPosition 必须用旧方向（lastOrientation）来判断保存到哪个 key，
     * 否则横屏坐标会被错误地保存到竖屏 key，导致位置互相污染。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "配置变化：保存旧方向位置 → 恢复新方向位置 → 钳位")

        // 从 lastOrientation 推断旧方向（正在离开的方向）
        val oldIsLandscape: Boolean? = when (lastOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> true
            Configuration.ORIENTATION_PORTRAIT -> false
            else -> null  // 首次切换，旧方向未知
        }

        floatingView?.let { view ->
            // 保存当前位置到旧方向对应的 key（非首次切换时）
            if (oldIsLandscape != null) {
                view.saveCurrentPosition(oldIsLandscape)
            }
            // 从新方向对应的 key 恢复位置（clampToScreenBounds 内部用 currentWindowMetrics 判断新方向）
            view.clampToScreenBounds(restorePosition = true)
        }

        // 更新 lastOrientation 为新方向
        lastOrientation = newConfig.orientation
    }

    override fun onDestroy() {
        isRunning = false
        // 注意：不在这里设置 FLOATING_WAS_RUNNING = false
        // 因为系统杀进程也会触发 onDestroy，但开机自启时需要恢复
        // 只在 HomeScreen 中用户手动关闭时才设置 false
        cancelHeartbeat()
        // 用户主动关闭悬浮窗（HomeScreen 已把 FLOATING_WAS_RUNNING 置 false）时取消看门狗周期任务，
        // 避免任务每 15 分钟空跑唤醒；系统杀进程时标志仍为 true，看门狗保活通道不受影响
        if (!prefs.getBoolean(PREF_FLOATING_RUNNING, false)) {
            KeepAliveJobService.cancel(this)
        }
        stopMonitoring()
        removeFloatingWindow()
        removeAliveOverlay()
        mainHandler.removeCallbacks(a11yPatrolRunnable)
        // 取消注册 SharedPreferences 监听器，防止内存泄漏
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null

        super.onDestroy()
    }

    /**
     * 当用户从最近任务中划掉应用时回调
     * 通过 AlarmManager 延迟 1 秒重启 Service
     * v1.65: 增加异常捕获，兼容 Android 12+ 前台服务启动限制
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 服务仍存活时（如按 Home 键触发 finishAndRemoveTask）无需重启，避免无谓的服务重建
        if (isRunning) {
            Log.d(TAG, "onTaskRemoved: 服务仍在运行，跳过重启")
            super.onTaskRemoved(rootIntent)
            return
        }
        Log.i(TAG, "onTaskRemoved: 应用被划掉，延迟重启")
        val restartIntent = Intent(applicationContext, FloatingWindowService::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            applicationContext,
            0,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        // 与心跳一致使用 setExactAndAllowWhileIdle 突破 Doze，确保划掉后准时重启
        // USE_EXACT_ALARM 为 normal 权限，侧载安装自动授予；权限缺失时降级为 set，避免崩溃
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                pendingIntent
            )
            Log.d(TAG, "已调度 onTaskRemoved 精确重启")
        } catch (e: SecurityException) {
            Log.w(TAG, "精确重启调度失败（权限不足），降级为普通闹钟: ${e.message}")
            try {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 1000,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e(TAG, "降级重启调度也失败", e2)
            }
        } catch (e: Exception) {
            Log.w(TAG, "onTaskRemoved 重启调度失败: ${e.message}", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    // ===== 心跳保活 =====

    /**
     * 设置 AlarmManager 精确心跳
     * 每 15 分钟触发 onStartCommand，确保 Service 持续存活
     *
     * v1.65: 从 setRepeating 升级为 setExactAndAllowWhileIdle
     * - setRepeating 在 Doze 模式下会被聚合延迟，实际触发远超设定间隔
     * - setExactAndAllowWhileIdle 突破 Doze 限制，确保准时触发
     * - 该方法是一次性的，需在每次 onStartCommand 中重新调度
     * - Android 12+ 需要 USE_EXACT_ALARM 权限（已在 Manifest 声明）
     */
    private fun scheduleHeartbeat() {
        // 无障碍保活在位时进程由系统绑定保活（崩溃秒级重绑），跳过 15 分钟周期唤醒以省电
        if (KeepAliveAccessibilityService.isRunning) return
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            val pendingIntent = heartbeatPendingIntent(applicationContext)
            // 使用 setExactAndAllowWhileIdle 突破 Doze 限制，确保 15 分钟准时触发
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                pendingIntent
            )
            Log.d(TAG, "精确心跳已设置: 间隔 ${HEARTBEAT_INTERVAL_MS / 60000} 分钟")
        } catch (e: SecurityException) {
            // 权限不足时降级为 setRepeating，保证至少有基础保活
            Log.e(TAG, "设置精确心跳失败（权限不足），降级为普通闹钟", e)
            try {
                val pendingIntent = heartbeatPendingIntent(applicationContext)
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                    HEARTBEAT_INTERVAL_MS,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e(TAG, "降级心跳设置也失败", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置心跳失败", e)
        }
    }

    /** 取消心跳 */
    private fun cancelHeartbeat() {
        cancelHeartbeatAlarm(applicationContext)
    }

    // ===== 悬浮窗管理 =====

    private fun showFloatingWindow() {
        if (floatingView != null) return

        floatingView = FloatingWindowView(this).also { view ->
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
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
            try {
                windowManager.addView(view, params)
            } catch (e: SecurityException) {
                Log.e(TAG, "添加悬浮窗失败：缺少悬浮窗权限", e)
                isRunning = false
                prefs.edit().putBoolean(PREF_FLOATING_RUNNING, false).apply()
                stopSelf()
                return
            } catch (e: Exception) {
                Log.e(TAG, "添加悬浮窗失败: ${e.message}", e)
                isRunning = false
                prefs.edit().putBoolean(PREF_FLOATING_RUNNING, false).apply()
                stopSelf()
                return
            }
            // 恢复上次保存的位置（按当前屏幕方向），无保存值则钳位到屏幕内
            view.clampToScreenBounds(restorePosition = true)
        }

        // 初始化 lastOrientation，确保首次 onConfigurationChanged 时有正确的旧方向
        lastOrientation = resources.configuration.orientation

        // 注册 SharedPreferences 监听器，仅监听外观相关 key 变化
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in APPEARANCE_KEYS) {
                floatingView?.reloadAppearance()
            }
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
        // PrivBatteryProvider 内部持有基础档:通道未连接时整体委托,开关切换无需重启监控
        batteryMonitor = BatteryMonitor(this, view, PrivBatteryProvider(this)).also {
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
     * v1.65: 增加失败延迟重试机制，确保窗口服务就绪后能成功添加
     */
    private fun addAliveOverlay() {
        if (aliveView != null) return
        // 无障碍保活运行时，更高层级的 TYPE_ACCESSIBILITY_OVERLAY 已承担保活，无需重复低层级窗口
        if (KeepAliveAccessibilityService.isRunning) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val lp = buildOverlayLayoutParams()
        try {
            wm.addView(view, lp)
            aliveView = view
            Log.d(TAG, "1x1 保活覆盖层添加成功")
        } catch (e: Exception) {
            Log.w(TAG, "添加保活覆盖层失败: ${e.message}")
            aliveView = null  // 确保状态一致
            // 延迟 3 秒后重试一次（确保窗口服务就绪）
            mainHandler?.postDelayed({ tryAddAliveOverlayRetry() }, 3000)
        }
    }

    /** 保活覆盖层重试添加（延迟 3 秒后执行一次） */
    private fun tryAddAliveOverlayRetry() {
        if (aliveView != null) return
        // 同 addAliveOverlay：无障碍 overlay 在位时跳过
        if (KeepAliveAccessibilityService.isRunning) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val lp = buildOverlayLayoutParams()
        try {
            wm.addView(view, lp)
            aliveView = view
            Log.d(TAG, "保活覆盖层重试添加成功")
        } catch (e: Exception) {
            Log.w(TAG, "保活覆盖层重试仍失败: ${e.message}")
            aliveView = null
        }
    }

    /** 构建 1x1 保活覆盖层布局参数 */
    private fun buildOverlayLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
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
            aliveView = null  // 确保状态一致
        }
    }

    // ===== 通知管理 =====

    private fun buildForegroundNotification(): Notification = Notifs.floatingForeground(this)
}
