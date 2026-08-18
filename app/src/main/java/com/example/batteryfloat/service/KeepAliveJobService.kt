package com.example.batteryfloat.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.batteryfloat.PrefsKeys

/**
 * 保活兜底看门狗（JobScheduler，15 分钟周期）
 * - 作为 AlarmManager 心跳的第二通道，防止部分 ROM 限制闹钟后服务失联
 * - 只在「开机自启开启 + 用户曾开启悬浮窗」且服务已死亡时尝试重启
 * - 重启走 AlarmManager 精确闹钟（与 onTaskRemoved 同一路径），不直接后台起前台服务
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJobService"
        private const val JOB_ID = 2001
        private const val PERIOD_MS = 15 * 60 * 1000L  // 15 分钟周期

        /**
         * 调度周期看门狗任务（幂等：同一 jobId 重复 schedule 会复用）
         * @param context 上下文
         */
        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
                val jobInfo = android.app.job.JobInfo.Builder(
                    JOB_ID,
                    android.content.ComponentName(context, KeepAliveJobService::class.java)
                )
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_NONE)
                    .setPersisted(false)
                    .build()
                scheduler.schedule(jobInfo)
            } catch (e: Exception) {
                Log.w(TAG, "调度看门狗任务失败: ${e.message}")
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val shouldKeepAlive = prefs.getBoolean(PrefsKeys.BOOT_AUTO_START, true) &&
                prefs.getBoolean(PrefsKeys.FLOATING_WAS_RUNNING, false)
        if (!shouldKeepAlive) {
            Log.d(TAG, "用户未开启悬浮窗或关闭自启，跳过")
            return false
        }
        if (FloatingWindowService.isRunning) {
            Log.d(TAG, "悬浮窗服务仍存活，跳过")
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，跳过重启")
            return false
        }
        scheduleRestart()
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    /** 通过 AlarmManager 精确闹钟重启悬浮窗服务（与 onTaskRemoved 一致） */
    private fun scheduleRestart() {
        val restartIntent = Intent(applicationContext, FloatingWindowService::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            applicationContext,
            2,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                pendingIntent
            )
            Log.i(TAG, "已调度看门狗重启闹钟")
        } catch (e: SecurityException) {
            Log.w(TAG, "精确闹钟权限不足，降级为普通闹钟: ${e.message}")
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
            Log.w(TAG, "调度重启闹钟失败: ${e.message}", e)
        }
    }
}
