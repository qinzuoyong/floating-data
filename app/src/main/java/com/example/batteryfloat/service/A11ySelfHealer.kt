package com.example.batteryfloat.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.adb.PrivShell
import com.example.batteryfloat.notif.Notifs
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍保活自愈器(批次 5)
 *
 * 无障碍保活被系统/厂商策略意外关闭后,把自己写回 ENABLED_ACCESSIBILITY_SERVICES:
 * - 主路径:持 WRITE_SECURE_SETTINGS 时应用直接写回,不依赖 ADB 通道活性;
 *   权限未到手时借内置 ADB 通道执行 pm grant 自授(一次授权持久有效,重启保留)
 * - 辅路径:grant 不可用的机型退化用 shell 流直接 settings put 写回(依赖通道在线)
 *
 * 触发:无障碍 onDestroy 后延迟数秒(系统解绑收尾) + 悬浮窗服务周期巡检。
 * 安全阀:
 * - 门控——用户在应用内主动关闭(走 disableSelf 前打标记)永不自愈;
 *   仅「系统侧被关而应用侧未关」视为意外
 * - 退避——写回后复查仍未启用则按 1min→5min→30min→12h 退避,避免与厂商管控拉锯
 * - 知情——自愈成功发一条 4 秒自动消失的低优先级通知
 */
object A11ySelfHealer {

    private const val TAG = "A11ySelfHealer"

    /** 写回后延迟复查(给系统重新绑定留时间) */
    private const val RECHECK_DELAY_MS = 60_000L

    private val BACKOFF_STEPS_MS = longArrayOf(60_000L, 5 * 60_000L, 30 * 60_000L, 12 * 3_600_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一轮自愈进行中(写回→复查)时拒绝重入 */
    private val healing = AtomicBoolean(false)

    private var healAttempts = 0
    private var backoffUntil = 0L

    /**
     * 自愈入口:条件满足则写回并复查(非阻塞)。
     * @param trigger 触发来源(onDestroy/巡检),仅用于日志
     * @param delayMs 执行前延迟(onDestroy 触发时给系统解绑收尾留时间)
     */
    fun maybeHeal(context: Context, trigger: String, delayMs: Long = 0L) {
        val ctx = context.applicationContext
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!healing.compareAndSet(false, true)) return@launch
            try {
                if (isUserDisabled(ctx)) return@launch
                if (KeepAliveAccessibilityService.isEnabledInSettings(ctx)) return@launch
                if (System.currentTimeMillis() < backoffUntil) return@launch
                Log.i(TAG, "检测到无障碍被关(触发:$trigger),尝试自愈")
                if (!writeBack(ctx)) {
                    onHealFailed(ctx)
                    return@launch
                }
                delay(RECHECK_DELAY_MS)
                if (KeepAliveAccessibilityService.isEnabledInSettings(ctx)) {
                    Log.i(TAG, "自愈成功,无障碍已恢复")
                    healAttempts = 0
                    backoffUntil = 0
                    notifyHealed(ctx)
                } else {
                    onHealFailed(ctx)
                }
            } finally {
                healing.set(false)
            }
        }
    }

    /** 用户意图标记:应用内主动开启/关闭无障碍时维护,决定自愈是否触发 */
    fun markUserDisabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PrefsKeys.A11Y_USER_DISABLED, value).apply()
    }

    /**
     * 供 ADB 连接成功自动授权引导(AdbAutoGrant)复用:确保无障碍保活处于启用状态(幂等)。
     * 已启用直接返回 true;用户在应用内主动关闭过则尊重意图不动,返回 false;
     * 否则走 writeBack 的主/辅路径写回,写后即查系统设置判定生效(防假成功)。
     */
    suspend fun ensureEnabled(context: Context): Boolean {
        val ctx = context.applicationContext
        if (KeepAliveAccessibilityService.isEnabledInSettings(ctx)) return true
        if (isUserDisabled(ctx)) return false
        if (!writeBack(ctx)) return false
        return KeepAliveAccessibilityService.isEnabledInSettings(ctx)
    }

    // ===== 写回实现 =====

    /** 写回本服务到启用列表;返回是否执行了写入(生效与否由复查判定) */
    private suspend fun writeBack(ctx: Context): Boolean {
        // 主路径:已持权直接写
        if (hasSecureWritePermission(ctx)) return writeSelfDirect(ctx)
        // 权限未到手:借特权通道自授(PrivShell:Shizuku 优先/内置 ADB 后备;
        // 全部离线时返回 null,自然走辅路径)
        if (PrivShell.exec(
                "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
            ) != null && hasSecureWritePermission(ctx)
        ) {
            Log.i(TAG, "已借 ADB 通道授予 WRITE_SECURE_SETTINGS")
            return writeSelfDirect(ctx)
        }
        // 辅路径:shell 域直接写(追加保留既有服务)
        Log.i(TAG, "pm grant 不可用,退化 shell 写回")
        return PrivShell.exec(buildShellWriteCmd(ctx)) != null
    }

    private fun hasSecureWritePermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(
            ctx, "android.permission.WRITE_SECURE_SETTINGS"
        ) == PackageManager.PERMISSION_GRANTED

    /** 持权直写:追加本服务到列表尾部并确保总开关为 1 */
    private fun writeSelfDirect(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val cn = ComponentName(ctx, KeepAliveAccessibilityService::class.java).flattenToString()
        return try {
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val already = !current.isNullOrBlank() &&
                    current.split(':').any { it.equals(cn, true) }
            if (!already) {
                val newValue = if (current.isNullOrBlank()) cn else "$current:$cn"
                if (!Settings.Secure.putString(
                        cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue
                    )
                ) return false
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: Exception) {
            Log.w(TAG, "直写启用列表失败: ${e.message}")
            false
        }
    }

    /** 辅路径一条命令:读现状→未含本服务则追加→补总开关(case 冒号定界防前缀误匹配) */
    private fun buildShellWriteCmd(ctx: Context): String {
        val cn = ComponentName(ctx, KeepAliveAccessibilityService::class.java).flattenToString()
        return "v=\$(settings get secure enabled_accessibility_services); " +
                "case \"\$v\" in null|NULL|'') v='';; esac; " +
                "case \":\$v:\" in *:\$cn:*) ;; *) " +
                "settings put secure enabled_accessibility_services \"\${v:+\$v:}$cn\";; esac; " +
                "settings put secure accessibility_enabled 1"
    }

    // ===== 退避与通知 =====

    private fun onHealFailed(ctx: Context) {
        val step = BACKOFF_STEPS_MS[healAttempts.coerceAtMost(BACKOFF_STEPS_MS.size - 1)]
        healAttempts++
        backoffUntil = System.currentTimeMillis() + step
        Log.w(TAG, "自愈未生效,退避 ${step / 1000}s 后自动重试(第 $healAttempts 次)")
        // 退避期满自动重试一次;不依赖外部触发源(onDestroy/巡检)以免冷启动场景恢复过慢
        scope.launch {
            delay(step)
            maybeHeal(ctx, trigger = "retry")
        }
    }

    private fun isUserDisabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PrefsKeys.A11Y_USER_DISABLED, false)

    private fun notifyHealed(ctx: Context) {
        try {
            Notifs.ensureChannels(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Notifs.ID_HEALED, Notifs.healed(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "发自愈通知失败: ${e.message}")
        }
    }
}
