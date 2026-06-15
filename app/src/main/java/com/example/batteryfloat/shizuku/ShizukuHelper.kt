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

    // ===== 温度读取 =====

    fun readTemperature(listener: OnTemperatureListener) {
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh"), null, null)
            val processClass = process.javaClass

            val outputMethod = processClass.getMethod("getOutputStream")
            val inputMethod = processClass.getMethod("getInputStream")
            val waitMethod = processClass.getMethod("waitFor")

            val outputStream = outputMethod.invoke(process) as java.io.OutputStream
            val inputStream = inputMethod.invoke(process) as java.io.InputStream

            val cmd = "cat $TEMP_PATH 2>/dev/null || cat $TEMP_PATH_ALT 2>/dev/null\n"
            outputStream.write(cmd.toByteArray())
            outputStream.flush()
            outputStream.close()

            val reader = BufferedReader(InputStreamReader(inputStream))
            val line = reader.readLine()
            reader.close()
            waitMethod.invoke(process)

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
}