package com.example.batteryfloat.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.batteryfloat.service.A11ySelfHealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ADB 通道连通后自动授权引导(2026-09 起默认常开,无开关)
 *
 * 特权通道每次连通(shell 自检 uid=2000 通过)后执行一次幂等检查,按需补齐:
 * 1. WRITE_SECURE_SETTINGS:pm grant 自授(一次授权持久,重启保留)
 * 2. 无障碍保活:复用 A11ySelfHealer 的主路径(持权直写)/辅路径(shell 写回);
 *    用户在应用内主动关闭过则跳过,尊重用户意图
 * 3. 悬浮窗权限:appops set 放行 SYSTEM_ALERT_WINDOW,免手动跳设置授权
 * 4. 定位权限:ACCESS_FINE/COARSE/BACKGROUND_LOCATION + POST_NOTIFICATIONS
 *    (家人位置共享的后台持续定位所需)
 * 5. 电池优化白名单:dumpsys deviceidle 加白,免系统弹窗手动确认
 *
 * 每步执行后读回验证,失败只记日志不改状态:雷电等 ROM 的 TLS 通道 shell 流
 * 对 pm grant/appops 会静默失败(假成功),读回验证可如实暴露
 */
object AdbAutoGrant {

    private const val TAG = "AdbAutoGrant"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 通道进入 CONNECTED 后由 AdbConnectionManager 调用;立即返回,检查在 IO 协程执行 */
    fun onConnected(context: Context) {
        val ctx = context.applicationContext
        scope.launch { runBootstrap(ctx) }
    }

    /** 依次补齐各项权限;各项相互独立,单项失败不影响后续 */
    private suspend fun runBootstrap(ctx: Context) {
        grantSecureSettings(ctx)
        ensureAccessibility(ctx)
        grantOverlay(ctx)
        grantLocation(ctx)
        grantBatteryWhitelist(ctx)
    }

    /** 步骤 1:WRITE_SECURE_SETTINGS 自授 */
    private suspend fun grantSecureSettings(ctx: Context) {
        if (hasPermission(ctx, "android.permission.WRITE_SECURE_SETTINGS")) return
        val out = PrivShell.exec(
            "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
        )
        if (hasPermission(ctx, "android.permission.WRITE_SECURE_SETTINGS")) {
            Log.i(TAG, "已授予 WRITE_SECURE_SETTINGS")
        } else {
            Log.w(TAG, "pm grant 未生效(输出=${out?.take(80)})")
        }
    }

    /** 步骤 2:无障碍保活启用(用户主动关闭过由 ensureEnabled 内部跳过) */
    private suspend fun ensureAccessibility(ctx: Context) {
        if (A11ySelfHealer.ensureEnabled(ctx)) {
            Log.i(TAG, "无障碍保活已确认启用")
        } else {
            Log.w(TAG, "无障碍保活未启用(被用户主动关闭或写回未生效)")
        }
    }

    /** 步骤 3:悬浮窗权限 appop 放行 */
    private suspend fun grantOverlay(ctx: Context) {
        if (Settings.canDrawOverlays(ctx)) return
        val out = PrivShell.exec(
            "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow"
        )
        if (Settings.canDrawOverlays(ctx)) {
            Log.i(TAG, "已授予悬浮窗权限(appops)")
        } else {
            Log.w(TAG, "appops set 未生效(输出=${out?.take(80)})")
        }
    }

    /** 步骤 4:家人位置共享定位权限(含后台定位与 FGS 通知) */
    private suspend fun grantLocation(ctx: Context) {
        for (perm in LOCATION_PERMISSIONS) {
            if (hasPermission(ctx, perm)) continue
            val out = PrivShell.exec("pm grant ${ctx.packageName} $perm")
            if (hasPermission(ctx, perm)) {
                Log.i(TAG, "已授予 $perm")
            } else {
                Log.w(TAG, "pm grant $perm 未生效(输出=${out?.take(80)})")
            }
        }
    }

    /** 步骤 5:电池优化白名单(dumpsys deviceidle 加白,免系统弹窗手动确认) */
    private suspend fun grantBatteryWhitelist(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
        val out = PrivShell.exec("dumpsys deviceidle whitelist +${ctx.packageName}")
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Log.i(TAG, "已加入电池优化白名单")
        } else {
            Log.w(TAG, "deviceidle whitelist 未生效(输出=${out?.take(80)})")
        }
    }

    private fun hasPermission(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(
        ctx, perm
    ) == PackageManager.PERMISSION_GRANTED

    private val LOCATION_PERMISSIONS = arrayOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.POST_NOTIFICATIONS"
    )
}
