package com.example.batteryfloat.util

import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.batteryfloat.service.KeepliveA11yService
import com.example.batteryfloat.shizuku.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 保活管理器
 *
 * 负责通过 Shizuku 启用/禁用 KeepliveA11yService 无障碍保活服务。
 * 参考 GKD 的方案：通过 WRITE_SECURE_SETTINGS 权限直接修改
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 来注册/注销无障碍服务。
 *
 * 需要环境：
 * - Shizuku 已授权
 * - WRITE_SECURE_SETTINGS 权限（通过 Shizuku 获取）
 */
object KeepaliveManager {

    private const val TAG = "KeepaliveManager"

    /**
     * 切换进程保活状态
     *
     * @param context 上下文
     * @param enable true=启用无障碍保活, false=关闭
     */
    suspend fun toggleKeepalive(context: Context, enable: Boolean) {
        withContext(Dispatchers.IO) {
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
     */
    private suspend fun enableKeepalive(context: Context) {
        // 检查 Shizuku 是否可用
        if (!ShizukuHelper.isRunning() || !ShizukuHelper.hasPermission()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "启用保活需要 Shizuku 授权\n请先连接 Shizuku 并授予权限",
                    Toast.LENGTH_LONG
                ).show()
            }
            Log.w(TAG, "Shizuku 未运行或未授权，无法启用无障碍保活")
            return
        }

        try {
            val cn = ComponentName(context, KeepliveA11yService::class.java)
            val cnStr = cn.flattenToShortString()

            // 通过 settings 命令注册无障碍服务
            val currentServices = getCurrentA11yServices()
            if (cnStr in currentServices) {
                Log.i(TAG, "无障碍保活服务已在启用列表中")
                return
            }

            val newServices = (currentServices + cnStr).joinToString(":")
            val result = ShizukuHelper.executeCommand(
                "settings put secure enabled_accessibility_services \"$newServices\""
            )

            if (result != null) {
                // 确保 accessibility_enabled = 1
                ShizukuHelper.executeCommand("settings put secure accessibility_enabled 1")
                Log.i(TAG, "无障碍保活服务已启用")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "进程保活已开启 ✓", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "启用无障碍保活服务失败")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "启用保活失败，请检查 Shizuku 权限", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启用无障碍保活异常", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "启用保活失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 禁用无障碍保活
     * 通过 Shizuku 将 KeepliveA11yService 从系统无障碍列表移除
     */
    private suspend fun disableKeepalive(context: Context) {
        if (!ShizukuHelper.isRunning()) {
            Log.w(TAG, "Shizuku 未运行，跳过禁用无障碍保活")
            return
        }

        try {
            val cn = ComponentName(context, KeepliveA11yService::class.java)
            val cnStr = cn.flattenToShortString()

            val currentServices = getCurrentA11yServices()
            val newServices = currentServices.filter { it != cnStr }.joinToString(":")

            val result = ShizukuHelper.executeCommand(
                "settings put secure enabled_accessibility_services \"$newServices\""
            )

            if (result != null) {
                Log.i(TAG, "无障碍保活服务已禁用")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "进程保活已关闭", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "禁用无障碍保活服务失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "禁用无障碍保活异常", e)
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
}