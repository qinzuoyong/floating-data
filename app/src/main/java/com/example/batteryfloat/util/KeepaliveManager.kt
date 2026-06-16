package com.example.batteryfloat.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 * 双策略启用无障碍保活服务：
 * 1. 优先使用 ADB/Shizuku 权限 — 静默注册，无需用户操作
 * 2. 降级到系统无障碍设置 — 引导用户手动开启
 *
 * 禁用时：
 * - ADB 可用：静默移除
 * - ADB 不可用：引导用户手动关闭
 */
object KeepaliveManager {

    private const val TAG = "KeepaliveManager"
    private const val SERVICE_STARTUP_WAIT_MS = 4000L

    /**
     * 切换进程保活状态
     *
     * @param context 上下文
     * @param enable true=启用, false=关闭
     * @return true=操作成功, false=操作失败（UI 应恢复开关状态）
     */
    suspend fun toggleKeepalive(context: Context, enable: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            if (enable) enableKeepalive(context) else disableKeepalive(context)
        }
    }

    // ==================== 启用保活 ====================

    private suspend fun enableKeepalive(context: Context): Boolean {
        // 如果已在运行，直接返回
        if (KeepliveA11yService.isRunning) {
            Log.i(TAG, "无障碍保活服务已在运行")
            return true
        }

        // 策略1：尝试 ADB/Shizuku 静默启用
        if (ShizukuHelper.isRunning() && ShizukuHelper.hasPermission()) {
            Log.i(TAG, "Shizuku 可用，尝试 ADB 静默启用")
            val adbResult = enableViaAdb(context)
            if (adbResult) return true
            Log.w(TAG, "ADB 启用失败，降级到系统设置")
        } else {
            Log.i(TAG, "Shizuku 不可用，直接引导系统设置")
        }

        // 策略2：引导用户到系统无障碍设置
        return enableViaSystemSettings(context)
    }

    /**
     * 策略1：通过 ADB/Shizuku 静默启用无障碍服务
     * 使用 settings put 命令直接注册到系统无障碍列表
     */
    private suspend fun enableViaAdb(context: Context): Boolean {
        try {
            val cn = ComponentName(context, KeepliveA11yService::class.java)
            val cnStr = cn.flattenToShortString()

            // 获取当前已启用的无障碍服务列表
            val currentServices = getCurrentA11yServices()
            if (cnStr !in currentServices) {
                // 添加到启用列表
                val newServices = (currentServices + cnStr).joinToString(":")
                Log.i(TAG, "ADB: settings put enabled_accessibility_services")
                val putResult = ShizukuHelper.executeCommand(
                    "settings put secure enabled_accessibility_services \"$newServices\""
                )
                Log.i(TAG, "settings put 结果: ${if (putResult != null) "成功" else "失败"}")

                // 确保无障碍总开关打开
                ShizukuHelper.executeCommand("settings put secure accessibility_enabled 1")
            } else {
                Log.i(TAG, "服务已在启用列表中")
            }

            // 等待系统启动无障碍服务
            Log.i(TAG, "等待无障碍服务启动（最多 ${SERVICE_STARTUP_WAIT_MS}ms）...")
            val startTime = System.currentTimeMillis()
            while (!KeepliveA11yService.isRunning &&
                System.currentTimeMillis() - startTime < SERVICE_STARTUP_WAIT_MS) {
                delay(500)
            }

            if (KeepliveA11yService.isRunning) {
                Log.i(TAG, "ADB 启用成功 ✓")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "进程保活已开启 ✓", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "ADB 启用异常", e)
            return false
        }
    }

    /**
     * 策略2：引导用户到系统无障碍设置界面手动开启
     * 打开系统无障碍设置，用户可在列表中找到并启用本应用的无障碍服务
     */
    private suspend fun enableViaSystemSettings(context: Context): Boolean {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                "请在无障碍设置中手动开启「勇哥」服务",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "无法打开无障碍设置", e)
                Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
            }
        }

        // 等待用户手动操作后检查结果
        Log.i(TAG, "等待用户手动开启无障碍服务...")
        val startTime = System.currentTimeMillis()
        while (!KeepliveA11yService.isRunning &&
            System.currentTimeMillis() - startTime < SERVICE_STARTUP_WAIT_MS) {
            delay(500)
        }

        if (KeepliveA11yService.isRunning) {
            Log.i(TAG, "用户手动启用成功 ✓")
            return true
        }

        Log.w(TAG, "用户未在规定时间内开启无障碍服务")
        return false
    }

    // ==================== 禁用保活 ====================

    private suspend fun disableKeepalive(context: Context): Boolean {
        // 服务未运行，直接返回
        if (!KeepliveA11yService.isRunning) {
            Log.i(TAG, "无障碍保活服务未运行，无需禁用")
            return true
        }

        // 策略1：尝试 ADB/Shizuku 静默禁用
        if (ShizukuHelper.isRunning() && ShizukuHelper.hasPermission()) {
            val adbResult = disableViaAdb(context)
            if (adbResult) return true
        }

        // 策略2：引导用户到系统设置手动关闭
        return disableViaSystemSettings(context)
    }

    /**
     * 通过 ADB/Shizuku 静默禁用无障碍服务
     */
    private suspend fun disableViaAdb(context: Context): Boolean {
        try {
            val cn = ComponentName(context, KeepliveA11yService::class.java)
            val cnStr = cn.flattenToShortString()

            val currentServices = getCurrentA11yServices()
            val newServices = currentServices.filter { it != cnStr }.joinToString(":")

            Log.i(TAG, "ADB: 移除无障碍保活服务")
            val result = ShizukuHelper.executeCommand(
                "settings put secure enabled_accessibility_services \"$newServices\""
            )
            Log.i(TAG, "禁用结果: ${if (result != null) "成功" else "失败"}")

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "进程保活已关闭", Toast.LENGTH_SHORT).show()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "ADB 禁用异常", e)
            return false
        }
    }

    /**
     * 引导用户到系统设置手动关闭无障碍服务
     */
    private suspend fun disableViaSystemSettings(context: Context): Boolean {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                "请在无障碍设置中手动关闭「勇哥」服务",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "无法打开无障碍设置", e)
            }
        }

        // 等待用户手动操作
        val startTime = System.currentTimeMillis()
        while (KeepliveA11yService.isRunning &&
            System.currentTimeMillis() - startTime < SERVICE_STARTUP_WAIT_MS) {
            delay(500)
        }

        return !KeepliveA11yService.isRunning
    }

    // ==================== 工具方法 ====================

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
