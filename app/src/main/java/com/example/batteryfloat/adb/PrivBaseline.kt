package com.example.batteryfloat.adb

import android.content.Context
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
 * 1. 密钥受信:经 daemon 尝试把本应用 ADB 公钥写入 /data/misc/adb/adb_keys;
 *    该步只"预埋信任",不依赖 adbd 是否监听 5555,故独立于 TCP 固化执行
 *    (vivo 拒绝 setprop 时旧逻辑会提前 return,导致 trust-key 永远不可达)。
 *    shell 域无权限写入时走经典 A_AUTH 授权弹窗兜底(仅一次,勾「一律允许」后永久受信)。
 * 2. TCP 固化:setprop persist.adb.tcp.port 5555,重启后 adbd 自动监听 5555。
 * 3. 环回验证:直连 127.0.0.1:5555 自检通过即视为受信(依赖步骤 2 成功)。
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

        // 步骤 1:密钥受信(把本应用 ADB 公钥写入 /data/misc/adb/adb_keys)。
        // 该步骤与 adbd 是否监听 5555 无关——只是"预埋信任",故独立于 TCP 固化执行:
        // vivo 拒绝 setprop persist.adb.tcp.port 时,旧逻辑会提前 return 导致 trust-key 永远不可达。
        // 写不进去(shell 域被 SELinux 拦)会如实返回 open-failed,由环回授权弹窗兜底。
        // 只尝试一次(幂等),避免每次连接反复写/刷日志。
        // 注意:密钥初始化是异步的(见 AdbConnectionManager.setup),key 可能尚未就绪。
        // 此时**不能**置"已尝试"标记——否则公钥从未写入、标记却已落盘,
        // 该设备将永远不再尝试 trust-key,环回自愈基座静默失效。
        if (!prefs.getBoolean(PrefsKeys.PRIV_BASELINE_KEY_TRIED, false)) {
            val key = AdbConnectionManager.peekKey()
            if (key == null) {
                Log.w(TAG, "ADB 密钥尚未就绪,trust-key 留待下次连接重试")
            } else {
                prefs.edit().putBoolean(PrefsKeys.PRIV_BASELINE_KEY_TRIED, true).apply()
                // adb_keys 的每行格式为「<base64 公钥> <name>」,而 adbPublicKey 已是该格式
                // (见 AdbKey.adbEncoded),必须原样写入——再整体 Base64 一次会写成非法公钥行。
                // 单引号包裹:内容含空格(name 段),不加引号会被 sh 拆成两个参数。
                val pubKeyLine = String(key.adbPublicKey, Charsets.ISO_8859_1)
                    .substringBefore('\u0000')
                    .trim()
                val resp = PrivShell.exec("trust-key '$pubKeyLine'")?.trim()
                Log.i(TAG, "trust-key 结果: $resp")
                AdbConnectionManager.logDiag(ctx, "基座:trust-key 写入结果=$resp")
            }
        }

        // 步骤 2:固化 adbd TCP 端口(setprop persist.adb.tcp.port 5555)。
        // 该步骤为环回直连提供端口;vivo 拒绝则放弃(重试 2 次),但不再影响上面的 trust-key。
        var tcpOk = prefs.getBoolean(PrefsKeys.PRIV_BASELINE_TCP, false)
        if (!tcpOk) {
            val tcpTries = prefs.getInt(PrefsKeys.PRIV_BASELINE_TCP_TRIES, 0)
            if (tcpTries >= 2) {
                Log.w(TAG, "TCP 固化被 ROM 拒绝,环回自愈不可用(退化:重开一次无线调试即可恢复)")
            } else {
                prefs.edit().putInt(PrefsKeys.PRIV_BASELINE_TCP_TRIES, tcpTries + 1).apply()
                PrivShell.exec("setprop persist.adb.tcp.port 5555")
                val got = PrivShell.exec("getprop persist.adb.tcp.port")?.trim()
                tcpOk = got == "5555"
                prefs.edit().putBoolean(PrefsKeys.PRIV_BASELINE_TCP, tcpOk).apply()
                Log.i(TAG, "adbd TCP 固化: ok=$tcpOk (got=$got)")
                AdbConnectionManager.logDiag(
                    ctx,
                    if (tcpOk) "基座:TCP固化成功"
                    else "基座:TCP固化失败${if (tcpTries >= 1) "(已放弃,vivo 拒绝)" else ""}(got=${got?.take(60)})"
                )
            }
        }

        // 步骤 3:环回验证(依赖 5555 端口,仅 TCP 固化成功后才有意义)。
        // 允许触发一次性授权弹窗,等用户勾「一律允许」后永久受信。
        if (tcpOk && !prefs.getBoolean(PrefsKeys.PRIV_BASELINE_TRUSTED, false)) {
            val trusted = AdbConnectionManager.verifyLoopbackTrust(ctx)
            prefs.edit().putBoolean(PrefsKeys.PRIV_BASELINE_TRUSTED, trusted).apply()
            AdbConnectionManager.logDiag(
                ctx,
                if (trusted) "基座:环回直连已受信" else "基座:环回未受信,等待用户授权"
            )
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
