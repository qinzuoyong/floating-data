package com.example.batteryfloat.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.service.A11ySelfHealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ADB 通道连通后自动授权引导(用户开关 [PrefsKeys.ADB_AUTO_GRANT] 控制,默认关闭)
 *
 * 特权通道每次连通(shell 自检 uid=2000 通过)后执行一次幂等检查,按需补齐:
 * 1. WRITE_SECURE_SETTINGS:pm grant 自授(一次授权持久,重启保留)
 * 2. 无障碍保活:复用 A11ySelfHealer 的主路径(持权直写)/辅路径(shell 写回);
 *    用户在应用内主动关闭过则跳过,尊重用户意图
 * 3. 悬浮窗权限:appops set 放行 SYSTEM_ALERT_WINDOW,免手动跳设置授权
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
        val prefs = ctx.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PrefsKeys.ADB_AUTO_GRANT, false)) return
        scope.launch { runBootstrap(ctx) }
    }

    /** 依次补齐三项权限;各项相互独立,单项失败不影响后续 */
    private suspend fun runBootstrap(ctx: Context) {
        grantSecureSettings(ctx)
        ensureAccessibility(ctx)
        grantOverlay(ctx)
    }

    /** 步骤 1:WRITE_SECURE_SETTINGS 自授 */
    private suspend fun grantSecureSettings(ctx: Context) {
        if (hasSecureWrite(ctx)) return
        val out = AdbConnectionManager.exec(
            "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
        )
        if (hasSecureWrite(ctx)) {
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
        val out = AdbConnectionManager.exec(
            "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow"
        )
        if (Settings.canDrawOverlays(ctx)) {
            Log.i(TAG, "已授予悬浮窗权限(appops)")
        } else {
            Log.w(TAG, "appops set 未生效(输出=${out?.take(80)})")
        }
    }

    private fun hasSecureWrite(ctx: Context) = ContextCompat.checkSelfPermission(
        ctx, "android.permission.WRITE_SECURE_SETTINGS"
    ) == PackageManager.PERMISSION_GRANTED
}
