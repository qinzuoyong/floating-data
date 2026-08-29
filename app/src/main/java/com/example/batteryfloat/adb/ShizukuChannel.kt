package com.example.batteryfloat.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 服务桥接(方案一:复用 Shizuku 常驻 server 进程)
 *
 * Shizuku 的 server 是 shell 域(uid=2000)守护进程,由无线调试 ADB 会话一次性拉起后
 * 脱离 adbd 存活(官方 starter 的 fork+setsid 守护化)——"关掉无线调试特权能力仍在"的载体。
 * 手机重启后 server 消失,需一次 ADB 连通来重新拉起。
 *
 * 本对象职责:通道连通后检测 server 不在跑时,借内置 ADB 通道替用户拉起
 * (与 Shizuku 管理器自身"通过无线调试启动"执行同一 starter、同一参数)。
 */
object ShizukuChannel {

    private const val TAG = "ShizukuChannel"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val starting = AtomicBoolean(false)

    /** Shizuku 管理器是否安装 */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** server 的 binder 是否可达(不管本应用有没有被授权) */
    fun serverAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /**
     * 通道进入 CONNECTED 后由 AdbConnectionManager 调用(幂等、失败只记日志):
     * server 未在跑时借 ADB 通道执行其 starter 拉起
     */
    fun onConnected(context: Context) {
        if (serverAlive()) return
        if (!starting.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        scope.launch {
            try {
                startViaAdb(ctx)
            } finally {
                starting.set(false)
            }
        }
    }

    private suspend fun startViaAdb(ctx: Context) {
        if (!isInstalled(ctx)) {
            Log.i(TAG, "Shizuku 管理器未安装,跳过拉起")
            return
        }
        val info = try {
            ctx.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        } catch (_: Exception) {
            return
        }
        val starter = File(info.nativeLibraryDir, "libshizuku.so")
        val apkPath = info.sourceDir
        if (!starter.exists()) {
            Log.w(TAG, "starter 不存在: ${starter.absolutePath}")
            return
        }
        Log.i(TAG, "借内置通道拉起 Shizuku 服务…")
        // starter 自带"杀旧实例→fork 守护化→exec app_process"逻辑,执行完即退出;
        // 输出仅诊断用(核心流程 Shizuku 官方 Starter.kt 同款参数)
        val out = AdbConnectionManager.exec("${starter.absolutePath} --apk=$apkPath")
        if (out == null) {
            Log.w(TAG, "starter 执行失败(通道不可用)")
            return
        }
        // server 起动需要时间,轮询 binder 就绪
        repeat(10) {
            if (serverAlive()) {
                Log.i(TAG, "Shizuku 服务已拉起(第 ${it + 1} 秒)")
                return
            }
            delay(1_000)
        }
        Log.w(TAG, "Shizuku 服务拉起后 binder 未就绪(输出=${out.take(120)})")
    }
}
