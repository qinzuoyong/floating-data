package com.example.batteryfloat.shizuku

import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 帮助类
 * - 通过 Shizuku 执行 shell 命令读取 sysfs 电池温度
 * - 提供 isRunning/hasPermission 供温度降级使用
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    private const val TEMP_PATH = "/sys/class/power_supply/battery/temp"
    private const val TEMP_PATH_ALT = "/sys/class/power_supply/bms/temp"

    /** 缓存反射 Method 对象（一次性查找，避免每次调用都反射） */
    private var _newProcessMethod: java.lang.reflect.Method? = null
    private var _getOutputMethod: java.lang.reflect.Method? = null
    private var _getInputMethod: java.lang.reflect.Method? = null
    private var _waitForMethod: java.lang.reflect.Method? = null

    /** 温度变化回调 */
    interface OnTemperatureListener {
        fun onTemperature(celsius: Float)
        fun onError(message: String)
    }

    // ===== 基础状态检查（供温度读取降级用） =====

    fun isRunning(): Boolean {
        return try {
            Shizuku.getBinder() != null || Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 懒初始化缓存反射 Method 对象
     * - 仅在首次使用时反射查找，后续直接复用
     */
    private fun ensureReflectionCache() {
        if (_newProcessMethod != null) return
        _newProcessMethod = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        // getOutputStream / getInputStream / waitFor 需要先获取 process 的 Class 对象再缓存
    }

    // ===== 温度读取 =====

    fun readTemperature(listener: OnTemperatureListener) {
        try {
            ensureReflectionCache()
            val newProcess = _newProcessMethod!!
            val process = newProcess.invoke(null, arrayOf("sh"), null, null)
            val processClass = process.javaClass

            // 缓存 process 子进程 Method（按进程类型缓存，此处 process 类型固定）
            if (_getOutputMethod == null) {
                _getOutputMethod = processClass.getMethod("getOutputStream")
                _getInputMethod = processClass.getMethod("getInputStream")
                _waitForMethod = processClass.getMethod("waitFor")
            }

            val outputStream = _getOutputMethod!!.invoke(process) as java.io.OutputStream
            val inputStream = _getInputMethod!!.invoke(process) as java.io.InputStream

            val cmd = "cat $TEMP_PATH 2>/dev/null || cat $TEMP_PATH_ALT 2>/dev/null\n"
            outputStream.write(cmd.toByteArray())
            outputStream.flush()
            outputStream.close()

            val reader = BufferedReader(InputStreamReader(inputStream))
            val line = reader.readLine()
            reader.close()
            _waitForMethod!!.invoke(process)

            if (line != null && line.isNotBlank()) {
                val rawValue = line.trim().toIntOrNull()
                if (rawValue != null) {
                    val celsius = if (rawValue > 200) rawValue / 1000f else rawValue.toFloat()
                    listener.onTemperature(celsius)
                } else {
                    listener.onError("无法解析温度值: $line")
                }
            } else {
                listener.onError("温度文件为空或不可读")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 读取温度失败", e)
            listener.onError(e.message ?: "未知错误")
        }
    }

    // ===== 通用命令执行 =====

    /**
     * 通过 Shizuku 执行任意 shell 命令并返回结果
     * 用于 KeepaliveManager 等模块执行 settings 命令
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出文本，失败返回 null
     */
    fun executeCommand(command: String): String? {
        try {
            ensureReflectionCache()
            val newProcess = _newProcessMethod!!
            val process = newProcess.invoke(null, arrayOf("sh"), null, null)
            val processClass = process.javaClass

            if (_getOutputMethod == null) {
                _getOutputMethod = processClass.getMethod("getOutputStream")
                _getInputMethod = processClass.getMethod("getInputStream")
                _waitForMethod = processClass.getMethod("waitFor")
            }

            val outputStream = _getOutputMethod!!.invoke(process) as java.io.OutputStream
            val inputStream = _getInputMethod!!.invoke(process) as java.io.InputStream

            outputStream.write("$command\n".toByteArray())
            outputStream.flush()
            outputStream.close()

            val reader = BufferedReader(InputStreamReader(inputStream))
            val result = reader.readText()
            reader.close()
            _waitForMethod!!.invoke(process)

            return result.trim().ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 执行命令失败: $command", e)
            return null
        }
    }
}