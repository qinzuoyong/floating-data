package com.example.batteryfloat.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.batteryfloat.PrefsKeys
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 特权 shell 统一入口:按载体模式与可用性自动路由执行通道
 *
 * 载体模式([CarrierMode],用户可选):
 * - BUILTIN(默认):内置 libbfd.so 守护进程 → 内置 ADB 通道 → null
 * - SHIZUKU:Shizuku 常驻服务 → 内置 ADB 通道 → null
 *
 * 两条常驻载体与 adbd 会话解耦,无线调试关闭后仍存活;内置 ADB 通道负责
 * 首次拉起与自愈重建,全部不可用时返回 null 由消费方降级
 */
object PrivShell {

    private const val TAG = "PrivShell"
    private const val EXEC_TIMEOUT_MS = 10_000L

    /** 特权通道载体 */
    enum class CarrierMode { BUILTIN, SHIZUKU }

    @Volatile
    private var mode = CarrierMode.BUILTIN

    /** 最近一次成功执行所用的通道("bfd"/"shizuku"/"adb"/"none"),状态展示用 */
    @Volatile
    var lastChannel: String = "none"
        private set

    /** 进程内初始化(AdbConnectionManager.setup 调用):读取持久化载体模式 */
    fun init(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        mode = if (prefs.getString(PrefsKeys.PRIV_CARRIER_MODE, "builtin") == "shizuku") {
            CarrierMode.SHIZUKU
        } else {
            CarrierMode.BUILTIN
        }
    }

    fun carrierMode(): CarrierMode = mode

    /** 切换载体模式(落盘 + 更新缓存);返回是否成功持久化 */
    fun setCarrierMode(context: Context, value: CarrierMode): Boolean {
        val ok = context.applicationContext
            .getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PrefsKeys.PRIV_CARRIER_MODE, value.name.lowercase()).commit()
        if (ok) mode = value
        return ok
    }

    /** Shizuku 服务是否可用(binder 存活且本应用已获授权) */
    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        Log.v(TAG, "Shizuku 不可用: ${e.message}")
        false
    }

    /** 是否存在任一可执行通道(供门控判断,不发命令) */
    fun canExec(): Boolean = when (mode) {
        CarrierMode.BUILTIN -> BfdChannel.alive() || AdbConnectionManager.isReady
        CarrierMode.SHIZUKU -> shizukuReady() || AdbConnectionManager.isReady
    }

    /**
     * 执行特权 shell 命令并收集 stdout
     * @return 输出文本;无可用通道或执行失败返回 null
     */
    suspend fun exec(command: String): String? {
        // 1) 所选载体的常驻服务
        when (mode) {
            CarrierMode.BUILTIN -> if (BfdChannel.alive()) {
                try {
                    return BfdChannel.exec(command)?.also { lastChannel = "bfd" }
                } catch (e: Throwable) {
                    Log.w(TAG, "内置服务执行失败,降级内置通道: ${e.message}")
                }
            }
            CarrierMode.SHIZUKU -> if (shizukuReady()) {
                try {
                    return runViaShizuku(command)
                } catch (e: Throwable) {
                    Log.w(TAG, "Shizuku 执行失败,降级内置通道: ${e.message}")
                }
            }
        }
        // 2) 内置 ADB 通道(负责首次拉起与自愈重建)
        return AdbConnectionManager.exec(command)?.also { lastChannel = "adb" }
    }

    /** 经 Shizuku 服务执行;Shizuku.newProcess 自 13.1 起私有,走 IShizukuService 官方接口 */
    private suspend fun runViaShizuku(command: String): String =
        withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            val service = IShizukuService.Stub.asInterface(
                ShizukuBinderWrapper(Shizuku.getBinder()!!)
            )
            val p = service.newProcess(arrayOf("sh", "-c", command), null, null)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(p.inputStream)
                    .bufferedReader().readText()
                    .also { lastChannel = "shizuku" }
                    .also { p.waitFor() }
            } finally {
                runCatching { p.destroy() }
            }
        } ?: throw IllegalStateException("Shizuku 执行超时")

    // ===== Shizuku 运行时权限(用户授权一次即永久,服务器重启后仍保留) =====

    private val requesting = AtomicBoolean(false)

    /** 授权结果监听:收到结果后即移除,避免重复回调 */
    private val permResultListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            requesting.set(false)
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku 授权结果: granted=$granted")
            Shizuku.removeRequestPermissionResultListener(this)
        }
    }

    /**
     * Shizuku 服务在跑但未授权时发起一次授权请求(弹 Shizuku 管理器的对话框)。
     * 由 UI 在页面恢复时调用;已在请求中/已授权/服务不在时为空操作。
     * 用户拒绝后不再重复弹窗(落盘标记,重装 Shizuku 服务不重置)
     */
    fun requestPermissionIfNeeded(context: Context) {
        if (requesting.get()) return
        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!alive) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PrefsKeys.SHIZUKU_PERM_REQUESTED, false)) return
        if (!requesting.compareAndSet(false, true)) return
        prefs.edit().putBoolean(PrefsKeys.SHIZUKU_PERM_REQUESTED, true).apply()
        try {
            Shizuku.addRequestPermissionResultListener(permResultListener)
            Shizuku.requestPermission(0)
            Log.i(TAG, "已发起 Shizuku 授权请求")
        } catch (e: Throwable) {
            requesting.set(false)
            Log.w(TAG, "发起 Shizuku 授权请求失败: ${e.message}")
        }
    }
}
