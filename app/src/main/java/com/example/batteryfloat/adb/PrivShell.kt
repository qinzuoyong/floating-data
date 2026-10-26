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

    /**
     * 高精度数据源开关(对应 UI「高精度数据源 (ADB)」)。
     *
     * 门控语义：它是用户对"是否使用特权通道"的唯一表达。关闭时必须在这里拦住，
     * 而不能只依赖 [AdbConnectionManager.isReady]——内置载体(libbfd.so)一旦拉起就
     * 脱离 adbd 常驻，`setEnabled(false)` 不会杀它，令牌也没有清除入口，于是仅凭
     * "载体存活"判断会让特权采样在用户以为已关闭后继续每 2 秒 fork 一次 shell。
     */
    @Volatile
    private var enabled = false

    /** 进程内初始化(AdbConnectionManager.setup 调用):读取持久化载体模式与开关 */
    fun init(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        mode = if (prefs.getString(PrefsKeys.PRIV_CARRIER_MODE, "builtin") == "shizuku") {
            CarrierMode.SHIZUKU
        } else {
            CarrierMode.BUILTIN
        }
        enabled = prefs.getBoolean(PrefsKeys.ADB_PRIV_ENABLED, false)
    }

    /**
     * 同步开关状态(AdbConnectionManager.setEnabled / onPaired 调用)。
     * 关闭时立即作废内置载体的存活缓存，避免 [canExec] 在 TTL 内仍报可用。
     */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) BfdChannel.invalidateAlive()
    }

    /** 高精度数据源开关是否开启(UI 与消费方共用的唯一判据) */
    fun isEnabled(): Boolean = enabled

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

    /**
     * 是否存在任一可执行通道(供门控判断,不发命令)。
     * suspend:BUILTIN 模式下需要探测 daemon 存活(网络 IO),不能在主线程直接调用。
     *
     * 开关关闭时恒为 false：这是"用户关掉高精度数据源后不再走特权 shell"的第一道闸。
     */
    suspend fun canExec(): Boolean {
        if (!enabled) return false
        return when (mode) {
            CarrierMode.BUILTIN -> BfdChannel.alive() || AdbConnectionManager.isReady
            CarrierMode.SHIZUKU -> shizukuReady() || AdbConnectionManager.isReady
        }
    }

    /**
     * 执行特权 shell 命令并收集 stdout
     * @return 输出文本;无可用通道或执行失败返回 null
     *
     * 开关关闭时一律返回 null（与"无可用通道"同义），让所有消费方按既有降级路径回落。
     * 这道闸必须放在 exec 内部而非各调用点：全项目有 11 处 exec 调用，只有
     * PrivBatteryProvider 一处先查了 canExec；A11ySelfHealer 的写回/重绑、
     * AdbAutoGrant 的授权、PrivBaseline 的 trust-key 都是直接调用。
     */
    suspend fun exec(command: String): String? {
        if (!enabled) return null
        // 1) 所选载体的常驻服务
        when (mode) {
            CarrierMode.BUILTIN -> if (BfdChannel.alive()) {
                // BfdChannel.exec 内部 runCatching,失败返回 null 而不抛异常;
                // 用 let 而非 ?.also 返回,使"daemon 存活但本次执行失败"能按类注释
                // 约定的链路继续降级到内置 ADB 通道,而不是直接把 null 交给消费方
                BfdChannel.exec(command)?.let { return it.also { lastChannel = "bfd" } }
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

    /**
     * 经 Shizuku 服务执行;Shizuku.newProcess 自 13.1 起私有,走 IShizukuService 官方接口。
     *
     * 资源纪律(2026-10 修复):`AutoCloseInputStream` 必须 use 关闭。旧实现
     * `AutoCloseInputStream(p.inputStream).bufferedReader().readText()` 读完只丢引用，
     * 流与底层 ParcelFileDescriptor 都不关闭；`p.destroy()` 只杀远端进程、不回收本端 FD。
     * 高精度采样每 2 秒一条命令 → 每小时约 1800 个 FD 泄漏，最终 Too many open files。
     *
     * 另注:`withTimeoutOrNull` 对阻塞的 readText()/waitFor() 无效(无挂起点)，
     * 真正兜底的是超时后的 destroy()；因此这里不依赖协程超时来"打断"读。
     */
    private suspend fun runViaShizuku(command: String): String =
        withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            val service = IShizukuService.Stub.asInterface(
                ShizukuBinderWrapper(Shizuku.getBinder()!!)
            )
            val p = service.newProcess(arrayOf("sh", "-c", command), null, null)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(p.inputStream).use { input ->
                    input.bufferedReader().use { reader ->
                        reader.readText()
                    }
                }.also { lastChannel = "shizuku" }
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
        try {
            Shizuku.addRequestPermissionResultListener(permResultListener)
            Shizuku.requestPermission(0)
            // 标记在请求**成功发起之后**才落盘:旧实现先落盘再请求,一旦
            // requestPermission 抛异常,标记已为 true → 之后一律 return,永不弹授权框。
            prefs.edit().putBoolean(PrefsKeys.SHIZUKU_PERM_REQUESTED, true).apply()
            Log.i(TAG, "已发起 Shizuku 授权请求")
        } catch (e: Throwable) {
            requesting.set(false)
            // 请求没发出去就把监听器撤掉,否则它会永久挂在 Shizuku 静态监听表里
            // (只有收到结果时才在回调里移除)
            runCatching { Shizuku.removeRequestPermissionResultListener(permResultListener) }
            Log.w(TAG, "发起 Shizuku 授权请求失败: ${e.message}")
        }
    }
}
