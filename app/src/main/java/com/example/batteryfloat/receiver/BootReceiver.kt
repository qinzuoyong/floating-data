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
 * 注意：`floating_was_running` 只在以下情况被设为 false：
 * - 用户通过 MainActivity 主动关闭悬浮窗
 * 而不是在 FloatingWindowService.onDestroy 中设置，
 * 因为系统杀死进程时也会触发 onDestroy，但我们希望开机自启时能恢复。
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

        // 延迟检查回调
        if (action == ACTION_AUTO_START_CHECK) {
            handleDelayedCheck(context)
            return
        }

        // 仅处理开机完成广播
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        Log.i(TAG, "收到开机广播: $action")

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

        val shouldRestart = !FloatingWindowService.isRunning &&
                prefs.getBoolean(PREF_BOOT_AUTO_START, true) &&
                prefs.getBoolean(PREF_FLOATING_RUNNING, false)

        if (shouldRestart) {
            Log.i(TAG, "延迟检查：服务未运行，自动重试启动")
            try {
                FloatingWindowService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "延迟检查：启动失败", e)
            }
        } else {
            Log.d(TAG, "延迟检查：服务状态正常或不需要启动")
        }
    }
}
