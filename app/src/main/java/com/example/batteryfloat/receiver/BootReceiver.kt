package com.example.batteryfloat.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.batteryfloat.service.FloatingWindowService

/**
 * 智能开机自启广播接收器
 *
 * 行为逻辑（受「开机自启动」开关控制）：
 * 1. 读取 SharedPreferences 中 `boot_auto_start` 开关（默认开启）
 * 2. 如果开关关闭 → 不做任何事
 * 3. 读取 `floating_was_running` 记录上次悬浮窗运行状态
 * 4. 如果上次是开启状态 → 自动启动悬浮窗服务
 * 5. 如果上次是关闭状态 → 静默退出
 * 6. 启动后延迟 15 秒再检查一次（防止系统开机阶段拉起失败）
 *
 * 原理参考 GKD 的智能启动策略：
 * GKD 并非每次开机都启动所有服务，而是根据用户上次关闭前的状态决策
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREF_BOOT_AUTO_START = "boot_auto_start"
        private const val PREF_FLOATING_RUNNING = "floating_was_running"
        private const val ACTION_AUTO_START_CHECK = "com.example.batteryfloat.action.AUTO_START_CHECK"
        private const val DELAYED_CHECK_MS = 15_000L
        private const val REQUEST_CODE_CHECK = 10001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_AUTO_START_CHECK) {
            // 延迟检查：开机一段时间后再次确认服务是否存活
            handleDelayedCheck(context)
            return
        }
        if (action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs: SharedPreferences =
            context.getSharedPreferences("floating_prefs", Context.MODE_PRIVATE)

        // 1. 检查「开机自启动」开关（默认开启）
        val bootAutoStart = prefs.getBoolean(PREF_BOOT_AUTO_START, true)
        if (!bootAutoStart) {
            Log.i(TAG, "开机自启动开关已关闭，跳过启动")
            return
        }

        // 2. 检查上次悬浮窗是否在运行
        val wasRunning = prefs.getBoolean(PREF_FLOATING_RUNNING, false)
        if (!wasRunning) {
            Log.i(TAG, "上次退出前悬浮窗未开启，跳过开机自启动")
            return
        }

        // 3. 启动悬浮窗服务
        Log.i(TAG, "检测到上次悬浮窗运行中 → 开机自动启动")
        try {
            FloatingWindowService.start(context)
            Log.i(TAG, "开机启动悬浮窗服务成功")

            // 4. 延迟 15 秒后再检查一次（防止系统开机阶段服务拉起失败）
            scheduleDelayedCheck(context)
        } catch (e: Exception) {
            Log.e(TAG, "开机启动服务失败", e)
            // 启动失败也要尝试延迟重试
            scheduleDelayedCheck(context)
        }
    }

    /** 设置延迟检查闹钟 */
    private fun scheduleDelayedCheck(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val checkIntent = Intent(context, BootReceiver::class.java).apply {
                    action = ACTION_AUTO_START_CHECK
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_CHECK,
                    checkIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + DELAYED_CHECK_MS,
                    pendingIntent
                )
                Log.d(TAG, "已设置延迟检查: ${DELAYED_CHECK_MS / 1000} 秒后")
            } catch (e: Exception) {
                Log.e(TAG, "设置延迟检查失败", e)
            }
        }
    }

    /** 延迟检查处理：服务未运行时自动重试启动 */
    private fun handleDelayedCheck(context: Context) {
        val prefs: SharedPreferences =
            context.getSharedPreferences("floating_prefs", Context.MODE_PRIVATE)

        if (!FloatingWindowService.isRunning &&
            prefs.getBoolean(PREF_BOOT_AUTO_START, true) &&
            prefs.getBoolean(PREF_FLOATING_RUNNING, false)
        ) {
            Log.i(TAG, "延迟检查：服务未运行，自动重试启动")
            try {
                FloatingWindowService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "延迟检查：启动失败", e)
            }
        }
    }
}