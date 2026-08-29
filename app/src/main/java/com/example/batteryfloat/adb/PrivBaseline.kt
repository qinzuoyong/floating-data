package com.example.batteryfloat.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.batteryfloat.PrefsKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自愈基座(方案三:一次连通,终身免维护)
 *
 * 通道首次连通(内置载体)后做一次性布防,达成"重启后零操作自愈":
 * 1. TCP 固化:setprop persist.adb.tcp.port 5555,重启后 adbd 自动监听 5555
 * 2. 密钥受信:经 daemon 尝试把本应用 ADB 公钥写入 /data/misc/adb/adb_keys;
 *    vivo 等机型 shell 域可能无权限——此时走经典 A_AUTH 授权弹窗(仅一次,
 *    用户勾「一律允许」后永久受信)
 * 3. 环回验证:直连 127.0.0.1:5555 自检通过即视为受信
 *
 * 三步全部就绪后落盘标记;此后 daemon 死亡/手机重启均可经环回直连自动恢复,
 * 无线调试不再被需要。vivo 可能拦截其中某步(如实记诊断日志),退化后仍保有
 * "重开一次无线调试即可恢复"的 Shizuku 级体验
 */
object PrivBaseline {

    private const val TAG = "PrivBaseline"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一轮基座推进进行中(防通道闪断期反复重入刷日志) */
    private val running = AtomicBoolean(false)

    /** 通道进入 CONNECTED 且载体为内置服务时调用(幂等,已完成则直接返回) */
    fun onConnected(context: Context) {
        val ctx = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                run(ctx)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun run(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PrefsKeys.PRIV_BASELINE_DONE, false)) return

        // TCP 固化是环回自愈的前提;ROM 拒绝(如 vivo)则基座整体不可用,不再触发授权弹窗
        val tcpTries = prefs.getInt(PrefsKeys.PRIV_BASELINE_TCP_TRIES, 0)
        if (!prefs.getBoolean(PrefsKeys.PRIV_BASELINE_TCP, false) && tcpTries >= 2) {
            Log.w(TAG, "TCP 固化被 ROM 拒绝,自愈基座不可用(退化:重开一次无线调试即可恢复)")
            return
        }

        // 步骤 1:固化 adbd TCP 端口(vivo 实测拒绝 shell 域设置该属性——重试 2 次后放弃并记录)
        if (!prefs.getBoolean(PrefsKeys.PRIV_BASELINE_TCP, false)) {
            prefs.edit().putInt(PrefsKeys.PRIV_BASELINE_TCP_TRIES, tcpTries + 1).apply()
            PrivShell.exec("setprop persist.adb.tcp.port 5555")
            val got = PrivShell.exec("getprop persist.adb.tcp.port")?.trim()
            val ok = got == "5555"
            prefs.edit().putBoolean(PrefsKeys.PRIV_BASELINE_TCP, ok).apply()
            Log.i(TAG, "adbd TCP 固化: ok=$ok (got=$got)")
            AdbConnectionManager.logDiag(
                ctx, if (ok) "基座:TCP固化成功" else "基座:TCP固化失败${if (tcpTries >= 1) "(已放弃,vivo 拒绝)" else ""}(got=${got?.take(60)})"
            )
            if (!ok) return
        }

        // 步骤 2+3:密钥受信 + 环回验证
        if (!prefs.getBoolean(PrefsKeys.PRIV_BASELINE_TRUSTED, false)) {
            val key = AdbConnectionManager.peekKey()
            if (key != null) {
                val pub = Base64.encodeToString(key.adbPublicKey, Base64.NO_WRAP)
                val resp = PrivShell.exec("trust-key $pub")?.trim()
                Log.i(TAG, "trust-key 结果: $resp")
                // 环回验证(允许触发授权弹窗,等用户点「一律允许」)
                val trusted = AdbConnectionManager.verifyLoopbackTrust(ctx)
                prefs.edit().putBoolean(PrefsKeys.PRIV_BASELINE_TRUSTED, trusted).apply()
                AdbConnectionManager.logDiag(
                    ctx,
                    if (trusted) "基座:环回直连已受信(trust-key=$resp)" else "基座:环回未受信(trust-key=$resp,等待用户授权)"
                )
            }
        }

        val done = prefs.getBoolean(PrefsKeys.PRIV_BASELINE_TCP, false) &&
                prefs.getBoolean(PrefsKeys.PRIV_BASELINE_TRUSTED, false)
        if (done) {
            prefs.edit().putBoolean(PrefsKeys.PRIV_BASELINE_DONE, true).apply()
            Log.i(TAG, "自愈基座就绪:重启后可零操作恢复")
            AdbConnectionManager.logDiag(ctx, "基座:自愈基座就绪")
        }
    }
}
