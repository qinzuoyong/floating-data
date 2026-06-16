package com.example.batteryfloat.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.example.batteryfloat.service.KeepliveA11yService
import com.example.batteryfloat.shizuku.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 保活管理器
 *
 * 负责通过 Shizuku 启用/禁用 KeepliveA11yService 无障碍保活服务。
 * 参考 GKD 的方案：通过 WRITE_SECURE_SETTINGS 权限直接修改
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 来注册/注销无障碍服务。
 *
 * 修复策略：
 * 1. 命令执行后验证服务是否真正启动
 * 2. 失败时回退到系统设置界面
 * 3. 返回操作结果，UI 据此更新开关状态
 */
object KeepaliveManager {

    private const val TAG = "KeepaliveManager"
    private const val SERVICE_STARTUP_WAIT_MS = 3000L

    /**
     * 切换进程保活状态
     *
     * @param context 上下文
     * @param enable true=启用无障碍保活, false=关闭
     * @return true=操作成功, false=操作失败（UI 应恢复开关状态）
     */
    suspend fun toggleKeepalive(context: Context, enable: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            if (enable) {
                enableKeepalive(context)
            } else {
                disableKeepalive(context)
            }
        }
    }

    /**
     * 启用无障碍保活
     * 通过 Shizuku 将 KeepliveA11yService 注册到系统无障碍列表
     *
     * @return true=成功, false=失败
     */
    private suspend fun enableKeepalive(context: Context): Boolean {
        // 1. 检查 Shizuku 是否可用
        if (!ShizukuHelper.isRunning() || !ShizukuHelper.hasPermission()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "启用保活需要 Shizuku 授权\n请先连接 Shizuku 并授予权限",
                    Toast.LENGTH_LONG
                ).show()
            }
            Log.w(TAG, "Shizuku 未运行或未授权，无法启用无障碍保活")
            return false
        }

        try {
            val cn = ComponentName(context, KeepliveA11yService::class.java)
            val cnStr = cn.flattenToShortString()

            // 2. 如果服务已经在运行，直接返回成功
            if (KeepliveA11yService.isRunning) {
                Log.i(TAG, "无障碍保活服务已在运行")
                return true
            }

            // 3. 获取当前已启用的无障碍服务列表
            val currentServices = getCurrentA11yServices()
            if (cnStr in currentServices) {
                // 已在列表中但未运行，等待系统启动
                Log.i(TAG, "服务已在启用列表中，等待系统启动...")
            } else {
                // 4. 将服务添加到启用列表
                val newServices = (currentServices + cnStr).joinToString(":")
                Log.i(TAG, "执行 settings put: $newServices")
                val putResult = ShizukuHelper.executeCommand(
                    "settings put secure enabled_accessibility_services \"$newServices\""
                )
                Log.i(TAG, "settings put 结果: ${if (putResult != null) "成功" else "失败(返回null)"}")

                // 5. 确保 accessibility_enabled = 1
                ShizukuHelper.executeCommand("settings put secure accessibility_enabled 1")
            }

            // 6. 等待系统启动无障碍服务
            Log.i(TAG, "等待无障碍服务启动（最多 ${SERVICE_STARTUP_WAIT_MS}ms）...")
            val startTime = System.currentTimeMillis()
            while (!KeepliveA11yService.isRunning &&
                System.currentTimeMillis() - startTime < SERVICE_STARTUP_WAIT_MS) {
                delay(500)
            }

            // 7. 验证启动结果
            if (KeepliveA11yService.isRunning) {
                Log.i(TAG, "无障碍保活服务已成功启动 ✓")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "进程保活已开启 ✓", Toast.LENGTH_SHORT).show()
                }
                return true
            } else {
                Log.e(TAG, "无障碍保活服务启动失败，引导用户手动开启")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "自动启用失败，请手动开启无障碍服务",
                        Toast.LENGTH_LONG
                    ).show()
                    // 引导用户到系统无障碍设置界面
                    try {
                        val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "无法打开无障碍设置", e)
                    }
                }
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启用无障碍保活异常", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "启用保活失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return false
        }
    }

    /**
     * 禁用无障碍保活
     * 通过 Shizuku 将 KeepliveA11yService 从系统无障碍列表移除
     *
     * @return true=成功, false=失败
     */
    private suspend fun disableKeepalive(context: Context): Boolean {
        if (!ShizukuHelper.isRunning()) {
            Log.w(TAG, "Shizuku 未运行，跳过禁用无障碍保活")
            return true  // 无法操作，返回 true 避免无限循环
        }

        try {
            val cn = ComponentName(context, KeepliveA11yService::class.java)
            val cnStr = cn.flattenToShortString()

            val currentServices = getCurrentA11yServices()
            val newServices = currentServices.filter { it != cnStr }.joinToString(":")

            Log.i(TAG, "执行 settings put 移除无障碍服务")
            val result = ShizukuHelper.executeCommand(
                "settings put secure enabled_accessibility_services \"$newServices\""
            )

            Log.i(TAG, "禁用无障碍保活结果: ${if (result != null) "成功" else "失败"}")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "进程保活已关闭", Toast.LENGTH_SHORT).show()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "禁用无障碍保活异常", e)
            return false
        }
    }

    /**
     * 获取当前已启用的无障碍服务列表
     */
    private fun getCurrentA11yServices(): List<String> {
        val result = ShizukuHelper.executeCommand(
            "settings get secure enabled_accessibility_services"
        )
        return if (result != null && result.isNotBlank()) {
            result.trim().split(":").filter { it.isNotBlank() }
        } else {
            emptyList()
        }
    }

    /**
     * 检查无障碍保活服务是否实际运行中
     */
    fun isKeepaliveRunning(): Boolean {
        return KeepliveA11yService.isRunning
    }
}
