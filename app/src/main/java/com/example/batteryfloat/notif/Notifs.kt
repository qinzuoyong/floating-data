package com.example.batteryfloat.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.batteryfloat.MainActivity
import com.example.batteryfloat.R

/**
 * 通知统一工厂(合并原四处构建器:悬浮窗前台/阈值刷新/自愈提示/ADB 配对)
 *
 * 平台语义备忘:`Notification.Builder.setTimeoutAfter` 的入参是【绝对时间戳】(epoch ms),
 * 原样写入 Notification.timeoutAfter,不是相对时长——所以"4 秒后消失"必须传
 * [autoDismissAfter]() 而不是 4_000。早期 AdbPairingService 用 currentTimeMillis()+4000(正确,
 * 真机已验证);自愈通知曾误用 4_000(会被系统视为 1970 年立即取消,通知看不到),合并时已修正。
 */
object Notifs {

    const val CHANNEL_BATTERY = "battery_temp_channel_v2"
    const val CHANNEL_PAIRING = "adb_pairing"
    const val CHANNEL_FAMILY = "family_location"

    const val ID_FLOATING = 1001
    const val ID_HEALED = 2001
    const val ID_PAIRING = 2002
    const val ID_FAMILY = 3001
    const val ID_FAMILY_NOTICE = 3002

    /** "n 秒后自动消失"所需的绝对时间戳(setTimeoutAfter 平台语义) */
    fun autoDismissAfter(ms: Long = 4_000L): Long = System.currentTimeMillis() + ms

    /** 幂等创建全部通知渠道;旧渠道 battery_temp_channel 已废弃,顺手清理(delete 幂等) */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("battery_temp_channel")
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BATTERY,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // 低优先级,状态栏显示小图标,防止被一键清理杀死
            ).apply {
                description = "电池温度悬浮窗监控服务"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PAIRING,
                "ADB 配对",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FAMILY,
                context.getString(R.string.notification_family_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "家人位置共享前台服务"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
    }

    /** 悬浮窗前台常驻通知(FloatingWindowService) */
    fun floatingForeground(context: Context): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_BATTERY)
            .setContentTitle(context.getString(R.string.notification_foreground_title))
            .setContentText(context.getString(R.string.notification_foreground_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN) // 最低优先级
            .setSilent(true) // 静默通知
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // 锁屏隐藏
            .setContentIntent(pendingIntent)
            .build()
    }

    /** 家人位置共享前台常驻通知(FamilyLocationService) */
    fun familyForeground(context: Context): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_FAMILY)
            .setContentTitle(context.getString(R.string.notification_family_title))
            .setContentText(context.getString(R.string.notification_family_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .build()
    }

    /** 家人共享启动失败提示（权限缺失等；点击进入应用） */
    fun familyStoppedNotice(context: Context, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_FAMILY)
            .setContentTitle("家人位置共享未启动")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()
    }

    /** 温度/功耗显著变化时刷新通知(BatteryMonitor) */
    fun floatingUpdate(context: Context, title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_BATTERY)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 无障碍自愈成功提示(4 秒自动消失) */
    fun healed(context: Context): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_BATTERY)
            .setContentTitle("无障碍保活已自动恢复")
            .setContentText("检测到服务被意外关闭,已自动写回")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(autoDismissAfter()) // 平台语义:绝对时间戳
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ===== ADB 配对通知(AdbPairingService,action 由调用方构造传入)=====
    // 配对通知沿用平台 Builder:其 RemoteInput/Action 走原生类型,与通知栏直回流程保持一致

    fun pairingSearching(context: Context, stopAction: Notification.Action): Notification =
        Notification.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在搜索配对服务…")
            .setContentText("请先在系统设置中打开「无线调试 → 使用配对码配对设备」")
            .setOngoing(true)
            .addAction(stopAction)
            .build()

    fun pairingInput(context: Context, replyAction: Notification.Action): Notification =
        Notification.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("已发现配对服务")
            .setContentText("点击「输入配对码」,直接回复系统弹窗显示的 6 位数字")
            .setOngoing(true)
            .addAction(replyAction)
            .build()

    fun pairingWorking(context: Context): Notification =
        Notification.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在配对…")
            .setOngoing(true)
            .build()

    fun pairingResult(
        context: Context,
        title: String,
        text: String,
        autoDismiss: Boolean
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        if (autoDismiss) {
            builder.setTimeoutAfter(autoDismissAfter())
        }
        return builder.build()
    }
}