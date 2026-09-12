package com.example.batteryfloat.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.service.A11ySelfHealer
import com.example.batteryfloat.service.KeepAliveAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * 对 pm grant/appops 会静默失败(假成功),读回验证可如实暴露。
 *
 * 知情与可逆(审查整改):凡是"由本模块自动授予"的项都会落盘记录,
 * 供 UI 展示实际状态并支持一键撤销——自动授权不应成为用户不可见的既成事实。
 */
object AdbAutoGrant {

    private const val TAG = "AdbAutoGrant"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 自动授权条目（落盘记录、UI 展示与撤销均以该枚举为准） */
    enum class AutoGrant(val label: String) {
        SECURE_SETTINGS("安全设置写入（WRITE_SECURE_SETTINGS）"),
        ACCESSIBILITY("无障碍保活写回"),
        OVERLAY("悬浮窗权限（appops）"),
        LOCATION("定位权限（精确 / 后台）"),
        NOTIFICATIONS("通知权限"),
        BATTERY_WHITELIST("电池优化白名单")
    }

    /** 自动授权记录项（UI 展示用：条目 + 当前实际是否生效） */
    data class AutoGrantItem(val kind: AutoGrant, val granted: Boolean)

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

    // ===== 自动授权 =====

    /** 步骤 1:WRITE_SECURE_SETTINGS 自授 */
    private suspend fun grantSecureSettings(ctx: Context) {
        if (hasPermission(ctx, "android.permission.WRITE_SECURE_SETTINGS")) return
        val out = PrivShell.exec(
            "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
        )
        if (hasPermission(ctx, "android.permission.WRITE_SECURE_SETTINGS")) {
            Log.i(TAG, "已授予 WRITE_SECURE_SETTINGS")
            recordGrant(ctx, AutoGrant.SECURE_SETTINGS)
        } else {
            Log.w(TAG, "pm grant 未生效(输出=${out?.take(80)})")
        }
    }

    /** 步骤 2:无障碍保活启用(用户主动关闭过由 ensureEnabled 内部跳过) */
    private suspend fun ensureAccessibility(ctx: Context) {
        if (A11ySelfHealer.ensureEnabled(ctx)) {
            Log.i(TAG, "无障碍保活已确认启用")
            recordGrant(ctx, AutoGrant.ACCESSIBILITY)
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
            recordGrant(ctx, AutoGrant.OVERLAY)
        } else {
            Log.w(TAG, "appops set 未生效(输出=${out?.take(80)})")
        }
    }

    /** 步骤 4:家人位置共享所需权限(含后台定位与通知) */
    private suspend fun grantLocation(ctx: Context) {
        for (perm in AUTO_PERMISSIONS) {
            if (hasPermission(ctx, perm)) continue
            val out = PrivShell.exec("pm grant ${ctx.packageName} $perm")
            if (hasPermission(ctx, perm)) {
                Log.i(TAG, "已授予 $perm")
                recordGrant(ctx, kindOf(perm))
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
            recordGrant(ctx, AutoGrant.BATTERY_WHITELIST)
        } else {
            Log.w(TAG, "deviceidle whitelist 未生效(输出=${out?.take(80)})")
        }
    }

    // ===== 记录 / 状态 / 撤销 =====

    /**
     * 已由本模块自动授权的条目（含当前实际生效状态），供 UI 展示与撤销入口使用。
     * 记录在授予成功时写入，用户手动授予的项不会被记入。
     */
    fun loggedItems(ctx: Context): List<AutoGrantItem> =
        loggedKinds(ctx).map { AutoGrantItem(it, isGranted(ctx, it)) }

    /** 当前该条目是否仍然生效（撤销后即为 false） */
    fun isGranted(ctx: Context, kind: AutoGrant): Boolean = when (kind) {
        AutoGrant.SECURE_SETTINGS -> hasPermission(ctx, "android.permission.WRITE_SECURE_SETTINGS")
        AutoGrant.ACCESSIBILITY -> KeepAliveAccessibilityService.isEnabledInSettings(ctx)
        AutoGrant.OVERLAY -> Settings.canDrawOverlays(ctx)
        AutoGrant.LOCATION -> hasPermission(ctx, "android.permission.ACCESS_FINE_LOCATION") ||
            hasPermission(ctx, "android.permission.ACCESS_COARSE_LOCATION")
        AutoGrant.NOTIFICATIONS -> hasPermission(ctx, "android.permission.POST_NOTIFICATIONS")
        AutoGrant.BATTERY_WHITELIST -> (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }

    /** 无障碍撤销后等待服务异步销毁的宽限期（disableSelf 不在命令返回时立即生效） */
    private const val ACCESSIBILITY_REVOKE_GRACE_MS = 1_000L

    /**
     * 撤销本模块自动授予的全部权限/开关（用户显式操作，逐项执行反向命令）。
     *
     * 每项撤销后**读回实际状态**复核：只有确实不再生效的条目才从记录中移除，
     * 撤销失败的条目保留在记录里，UI 仍可展示并重试——不能"命令发出去就当成功"，
     * 否则记录被清空后用户无从得知权限其实还在。
     * 无障碍保活走"标记用户主动关闭 + disableSelf"，避免自愈立即写回。
     *
     * @return 撤销失败的条目（空列表 = 全部撤销成功）
     */
    suspend fun revokeAutoGranted(ctx: Context): List<AutoGrant> {
        val kinds = loggedKinds(ctx)
        if (kinds.isEmpty()) return emptyList()
        val pkg = ctx.packageName
        val failed = mutableListOf<AutoGrant>()
        for (kind in kinds) {
            val result = when (kind) {
                AutoGrant.SECURE_SETTINGS ->
                    PrivShell.exec("pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS")
                AutoGrant.OVERLAY ->
                    PrivShell.exec("appops set $pkg SYSTEM_ALERT_WINDOW default")
                AutoGrant.LOCATION -> {
                    // 先撤后台定位再撤精确定位（顺序与系统依赖一致）
                    PrivShell.exec("pm revoke $pkg android.permission.ACCESS_BACKGROUND_LOCATION")
                    PrivShell.exec("pm revoke $pkg android.permission.ACCESS_FINE_LOCATION")
                    PrivShell.exec("pm revoke $pkg android.permission.ACCESS_COARSE_LOCATION")
                }
                AutoGrant.NOTIFICATIONS ->
                    PrivShell.exec("pm revoke $pkg android.permission.POST_NOTIFICATIONS")
                AutoGrant.BATTERY_WHITELIST ->
                    PrivShell.exec("dumpsys deviceidle whitelist -$pkg")
                AutoGrant.ACCESSIBILITY -> {
                    // 先打"用户主动关"标记，再关服务：onDestroy 的自愈钩子据此跳过，避免立即写回
                    A11ySelfHealer.markUserDisabled(ctx, true)
                    KeepAliveAccessibilityService.instance?.disableSelf()
                    // disableSelf 是异步销毁：等服务退出后再复核，避免把"尚未销毁"误判为失败
                    delay(ACCESSIBILITY_REVOKE_GRACE_MS)
                    null
                }
            }
            if (isGranted(ctx, kind)) {
                failed.add(kind)
                Log.w(TAG, "撤销 $kind 未生效(输出=${result?.take(80)})")
            } else {
                removeGrantLog(ctx, kind)
                Log.i(TAG, "已撤销 $kind (输出=${result?.take(80)})")
            }
        }
        return failed
    }

    private fun kindOf(perm: String): AutoGrant =
        if (perm == "android.permission.POST_NOTIFICATIONS") AutoGrant.NOTIFICATIONS
        else AutoGrant.LOCATION

    private fun recordGrant(ctx: Context, kind: AutoGrant) {
        val prefs = ctx.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PrefsKeys.AUTO_GRANT_LOG, emptySet()).orEmpty().toMutableSet()
        if (current.add(kind.name)) {
            prefs.edit().putStringSet(PrefsKeys.AUTO_GRANT_LOG, current).apply()
        }
    }

    private fun loggedKinds(ctx: Context): List<AutoGrant> {
        val names = ctx.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(PrefsKeys.AUTO_GRANT_LOG, emptySet()).orEmpty()
        return names.mapNotNull { name -> AutoGrant.entries.firstOrNull { it.name == name } }
    }

    /** 移除单条记录（该条已确认撤销生效时调用；不再整体清空，失败项得以保留供重试） */
    private fun removeGrantLog(ctx: Context, kind: AutoGrant) {
        val prefs = ctx.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PrefsKeys.AUTO_GRANT_LOG, emptySet()).orEmpty().toMutableSet()
        if (current.remove(kind.name)) {
            prefs.edit().putStringSet(PrefsKeys.AUTO_GRANT_LOG, current).apply()
        }
    }

    private fun hasPermission(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(
        ctx, perm
    ) == PackageManager.PERMISSION_GRANTED

    /** 由 pm grant 自动补齐的权限集合（定位三项 + 通知） */
    private val AUTO_PERMISSIONS = arrayOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.POST_NOTIFICATIONS"
    )
}
